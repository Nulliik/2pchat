package discovery

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestAnnounceRegistrationAndWakeupsRespectTrackerSchedule(t *testing.T) {
	var requests atomic.Int32
	tracker := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		fmt.Fprint(w, "d8:intervali1800e12:min intervali300e5:peers0:e")
	}))
	defer tracker.Close()
	s := NewDiscoveryService("self", 50001, nil, false, nil, nil)
	s.SetTrackers([]string{tracker.URL, tracker.URL})
	if err := s.SetSelfInfoHashes([]string{"self-hash"}, 50001); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var workers sync.WaitGroup
	s.dispatchDue(ctx, time.Now(), &workers)
	workers.Wait()
	for i := 0; i < 20; i++ {
		if err := s.RegisterInfoHash("self-hash"); err != nil {
			t.Fatal(err)
		}
		s.AnnounceAll()
		s.AnnounceHash("self-hash", decodeInfoHash("self-hash"))
		s.dispatchDue(ctx, time.Now().Add(29*time.Minute), &workers)
		workers.Wait()
	}
	if got := requests.Load(); got != 1 {
		t.Fatalf("unchanged registration sent %d requests, want 1", got)
	}
	s.dispatchDue(ctx, time.Now().Add(31*time.Minute), &workers)
	workers.Wait()
	if got := requests.Load(); got != 2 {
		t.Fatalf("due refresh sent %d requests, want 2", got)
	}
}

func TestAnnounceNetworkRefreshRespectsMinimum(t *testing.T) {
	s := NewDiscoveryService("self", 50001, nil, false, nil, nil)
	now := time.Now()
	state := &announceState{next: now.Add(30 * time.Minute), notBefore: now.Add(5 * time.Minute)}
	s.announces[announceKey{}] = state
	s.refreshSchedulesLocked(now)
	if !state.next.Equal(state.notBefore) {
		t.Fatal("network refresh ignored tracker minimum")
	}
	state.failures = 2
	state.next = now.Add(time.Hour)
	s.refreshSchedulesLocked(now)
	if !state.next.Equal(now.Add(time.Hour)) {
		t.Fatal("network refresh bypassed failure backoff")
	}
}

func TestAnnounceIntervalsAndBackoff(t *testing.T) {
	for _, test := range []struct {
		result *AnnounceResult
		want   time.Duration
	}{
		{nil, 15 * time.Minute}, {&AnnounceResult{Interval: -1}, 15 * time.Minute},
		{&AnnounceResult{Interval: 1}, time.Minute},
		{&AnnounceResult{Interval: 900, MinInterval: 1800}, 30 * time.Minute},
		{&AnnounceResult{Interval: int(^uint(0) >> 1)}, 15 * time.Minute},
	} {
		got, _ := trackerIntervals(test.result)
		if got != test.want {
			t.Fatalf("%+v: got %v want %v", test.result, got, test.want)
		}
	}
	for n := 1; n <= 20; n++ {
		got := announceFailureDelay(n)
		base := min(30*time.Second*time.Duration(1<<min(n-1, 7)), time.Hour)
		if got < base || got > base+base/10 {
			t.Fatalf("backoff %d: %v", n, got)
		}
	}
	parsed, err := ParseHTTPAnnounceResponse([]byte("d8:intervali900e12:min intervali1800e5:peers0:e"))
	if err != nil || parsed.MinInterval != 1800 {
		t.Fatalf("min interval: %+v %v", parsed, err)
	}
}

func TestSelfReplacementDoesNotEraseLookupsAndLookupsExpire(t *testing.T) {
	s := NewDiscoveryService("self", 50001, nil, false, nil, nil)
	_ = s.SetSelfInfoHashes([]string{"old"}, 50001)
	_ = s.RegisterInfoHash("friend")
	_ = s.SetSelfInfoHashes([]string{"new"}, 50001)
	if _, ok := s.infoHashes["old"]; ok {
		t.Fatal("old identity still registered")
	}
	if _, ok := s.infoHashes["friend"]; !ok {
		t.Fatal("self update erased peer lookup")
	}
	var workers sync.WaitGroup
	s.dispatchDue(context.Background(), time.Now().Add(lookupRegistrationTTL+time.Second), &workers)
	workers.Wait()
	if _, ok := s.infoHashes["friend"]; ok {
		t.Fatal("abandoned lookup did not expire")
	}
	if _, ok := s.infoHashes["new"]; !ok {
		t.Fatal("self publication expired")
	}
}

func TestAnnounceWorkersAreGloballyBoundedAndCancellationDrains(t *testing.T) {
	var live, peak atomic.Int32
	tracker := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := live.Add(1)
		defer live.Add(-1)
		for old := peak.Load(); n > old && !peak.CompareAndSwap(old, n); old = peak.Load() {
		}
		<-r.Context().Done()
	}))
	defer tracker.Close()
	s := NewDiscoveryService("self", 50001, nil, false, nil, nil)
	s.SetTrackers([]string{tracker.URL})
	for i := 0; i < 20; i++ {
		_ = s.RegisterInfoHash(fmt.Sprint(i))
	}
	ctx, cancel := context.WithCancel(context.Background())
	var workers sync.WaitGroup
	for i := 0; i < 10; i++ {
		s.dispatchDue(ctx, time.Now(), &workers)
	}
	s.mu.RLock()
	count := s.activeAnnounces
	s.mu.RUnlock()
	if count != maxTrackerWorkers {
		t.Fatalf("started %d workers", count)
	}
	cancel()
	workers.Wait()
	if peak.Load() > maxTrackerWorkers || s.activeAnnounces != 0 {
		t.Fatal("worker leak or limit bypass")
	}
}
