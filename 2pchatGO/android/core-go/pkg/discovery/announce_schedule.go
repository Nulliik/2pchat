package discovery

import (
	"context"
	"crypto/sha1"
	"encoding/hex"
	"errors"
	"math/rand/v2"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	DefaultAnnounceInterval = 15 * time.Minute
	minimumAnnounceInterval = time.Minute
	maximumAnnounceInterval = 24 * time.Hour
	lookupRegistrationTTL   = 30 * time.Minute
	maxLookupRegistrations  = 256
	maxTrackerWorkers       = 6
)

type announceKey struct {
	tracker string
	hash    [20]byte
}

type announceState struct {
	next, notBefore time.Time
	failures        int
	inFlight        bool
	refresh         bool
}

func decodeInfoHash(value string) [20]byte {
	var h [20]byte
	if b, err := hex.DecodeString(value); err == nil && len(b) == 20 {
		copy(h[:], b)
	} else if len(value) == 20 {
		copy(h[:], value)
	} else {
		h = sha1.Sum([]byte(value))
	}
	return h
}

// RegisterInfoHash leases a lookup registration. Re-registering only renews the
// lease; it never bypasses the per-tracker schedule or starts another worker.
func (s *DiscoveryService) RegisterInfoHash(value string) error {
	if strings.TrimSpace(value) == "" {
		return errors.New("empty discovery hash")
	}
	s.mu.Lock()
	if _, exists := s.infoHashes[value]; !exists && len(s.lookupExpiry) >= maxLookupRegistrations {
		s.mu.Unlock()
		return errors.New("too many discovery lookups")
	}
	s.infoHashes[value] = decodeInfoHash(value)
	s.lookupExpiry[value] = time.Now().Add(lookupRegistrationTTL)
	s.mu.Unlock()
	s.wakeAnnouncer()
	return nil
}

// SetSelfInfoHashes replaces this identity's publication set without deleting
// independent, short-lived peer lookups. Removed hashes stop being refreshed.
func (s *DiscoveryService) SetSelfInfoHashes(values []string, port int) error {
	if len(values) > 16 || port < 1 || port > 65535 {
		return errors.New("invalid discovery publication configuration")
	}
	hashes := make(map[string][20]byte, len(values))
	for _, value := range values {
		if strings.TrimSpace(value) == "" {
			return errors.New("empty discovery hash")
		}
		hashes[value] = decodeInfoHash(value)
	}
	s.mu.Lock()
	for value := range s.selfHashes {
		if _, lookup := s.lookupExpiry[value]; !lookup {
			delete(s.infoHashes, value)
		}
	}
	s.selfHashes = hashes
	for value, hash := range hashes {
		s.infoHashes[value] = hash
	}
	if s.listenPort != port {
		s.listenPort = port
		s.refreshSchedulesLocked(time.Now())
	}
	s.mu.Unlock()
	s.wakeAnnouncer()
	return nil
}

func (s *DiscoveryService) UnregisterInfoHash(value string) {
	s.mu.Lock()
	delete(s.infoHashes, value)
	delete(s.selfHashes, value)
	delete(s.lookupExpiry, value)
	s.mu.Unlock()
	s.wakeAnnouncer()
}

func (s *DiscoveryService) wakeAnnouncer() {
	select {
	case s.announceWake <- struct{}{}:
	default:
	}
}

// AnnounceAll and AnnounceHash are compatibility entry points. Every request
// goes through the same schedule, including manual and network wake-ups.
func (s *DiscoveryService) AnnounceAll() { s.wakeAnnouncer() }

func (s *DiscoveryService) AnnounceHash(_ string, _ [20]byte) { s.wakeAnnouncer() }

func (s *DiscoveryService) refreshSchedulesLocked(now time.Time) {
	for _, state := range s.announces {
		state.refresh = true
		if !state.inFlight && state.failures == 0 {
			state.next = laterTime(now, state.notBefore)
		}
	}
}

func laterTime(a, b time.Time) time.Time {
	if a.Before(b) {
		return b
	}
	return a
}

func trackerIntervals(result *AnnounceResult) (time.Duration, time.Duration) {
	interval, minimum := DefaultAnnounceInterval, minimumAnnounceInterval
	if result != nil {
		if result.Interval > 0 && result.Interval <= int(maximumAnnounceInterval/time.Second) {
			interval = time.Duration(result.Interval) * time.Second
		}
		if result.MinInterval > 0 && result.MinInterval <= int(maximumAnnounceInterval/time.Second) {
			minimum = max(minimum, time.Duration(result.MinInterval)*time.Second)
		}
	}
	return max(interval, minimum), minimum
}

func announceFailureDelay(failures int) time.Duration {
	base := min(30*time.Second*time.Duration(1<<min(max(failures-1, 0), 7)), time.Hour)
	return base + time.Duration(rand.Int64N(int64(base/10)+1))
}

// One scheduler owns worker creation. Stop cancels its context and waits for
// these workers without holding the state lock used by callbacks/completions.
func (s *DiscoveryService) periodicAnnounceLoop(ctx context.Context) {
	defer s.wg.Done()
	var workers sync.WaitGroup
	defer workers.Wait()
	timer := time.NewTimer(0)
	defer timer.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
		case <-s.announceWake:
		}
		if ctx.Err() != nil {
			return
		}
		delay := s.dispatchDue(ctx, time.Now(), &workers)
		if !timer.Stop() {
			select {
			case <-timer.C:
			default:
			}
		}
		timer.Reset(delay)
	}
}

func (s *DiscoveryService) dispatchDue(ctx context.Context, now time.Time, workers *sync.WaitGroup) time.Duration {
	// Policy checks acquire mu independently; never call them with mu held.
	s.mu.RLock()
	trackers := append([]string(nil), s.trackers...)
	s.mu.RUnlock()
	allowed := make(map[string]bool)
	for _, tracker := range trackers {
		if s.isTrackerAllowed(tracker) {
			allowed[tracker] = true
		}
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	for value, expiry := range s.lookupExpiry {
		if !now.Before(expiry) {
			delete(s.lookupExpiry, value)
			if _, self := s.selfHashes[value]; !self {
				delete(s.infoHashes, value)
			}
		}
	}
	hashes := make(map[[20]byte]bool)
	for _, hash := range s.infoHashes {
		hashes[hash] = true
	}
	for key, state := range s.announces {
		if (!allowed[key.tracker] || !hashes[key.hash]) && !state.inFlight {
			delete(s.announces, key)
		}
	}
	delay := lookupRegistrationTTL
	for _, expiry := range s.lookupExpiry {
		delay = min(delay, max(time.Millisecond, expiry.Sub(now)))
	}
	for tracker := range allowed {
		for hash := range hashes {
			key := announceKey{tracker, hash}
			state := s.announces[key]
			if state == nil {
				state = &announceState{}
				s.announces[key] = state
			}
			if state.inFlight {
				continue
			}
			if now.Before(state.next) {
				delay = min(delay, state.next.Sub(now))
				continue
			}
			if s.activeAnnounces >= maxTrackerWorkers {
				continue
			}
			state.inFlight = true
			state.refresh = false
			s.activeAnnounces++
			port, peerID := s.listenPort, s.peerID
			workers.Add(1)
			go func() {
				defer workers.Done()
				s.runAnnounce(ctx, key, state, peerID, port)
			}()
		}
	}
	return max(delay, time.Millisecond)
}

func (s *DiscoveryService) runAnnounce(parent context.Context, key announceKey, state *announceState, peerID [20]byte, port int) {
	started := time.Now()
	ctx, cancel := context.WithTimeout(parent, 8*time.Second)
	defer cancel()
	result, err := s.announceSingle(ctx, key.tracker, key.hash, peerID, port)
	now := time.Now()
	s.mu.Lock()
	state.inFlight = false
	s.activeAnnounces--
	if err == nil {
		interval, minimum := trackerIntervals(result)
		state.failures = 0
		state.notBefore = now.Add(minimum)
		state.next = now.Add(interval)
		if state.refresh {
			state.next = state.notBefore
		}
	} else {
		state.failures = min(state.failures+1, 8)
		state.next = laterTime(now.Add(announceFailureDelay(state.failures)), state.notBefore)
	}
	var aliases []string
	for value, hash := range s.infoHashes {
		if hash == key.hash {
			aliases = append(aliases, value)
		}
	}
	s.mu.Unlock()
	s.wakeAnnouncer()
	if parent.Err() != nil {
		return
	}
	s.reportTrackerStatus(key.tracker, result, started, err)
	if err == nil && result != nil && s.callback != nil {
		for _, alias := range aliases {
			for _, peer := range result.Peers {
				s.callback(alias, peer.Raw, "tracker")
			}
		}
	}
}

func (s *DiscoveryService) Start() error {
	s.lifecycleMu.Lock()
	defer s.lifecycleMu.Unlock()
	s.mu.Lock()
	if atomic.LoadInt32(&s.running) == 1 {
		s.mu.Unlock()
		return nil
	}
	s.ctx, s.cancel = context.WithCancel(context.Background())
	ctx := s.ctx
	atomic.StoreInt32(&s.running, 1)
	s.wg.Add(1)
	s.mu.Unlock()
	_ = s.lanEngine.Start()
	go s.periodicAnnounceLoop(ctx)
	return nil
}

func (s *DiscoveryService) Stop() error {
	s.lifecycleMu.Lock()
	defer s.lifecycleMu.Unlock()
	s.mu.Lock()
	if atomic.LoadInt32(&s.running) == 0 {
		s.mu.Unlock()
		return nil
	}
	atomic.StoreInt32(&s.running, 0)
	s.cancel()
	s.mu.Unlock()
	_ = s.lanEngine.Stop()
	s.wg.Wait()
	return nil
}
