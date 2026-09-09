// Package diagnostics collects opt-in, bounded, local connection summaries.
// It accepts enums and durations only, never endpoints, errors or identities.
package diagnostics

import (
	"sync"
	"time"
)

type Kind uint8

const (
	Direct Kind = iota
	Tor
	Yggdrasil
	Mixed
	Unknown
	kindCount
)

type Outcome uint8

const (
	Success Outcome = iota
	Rejected
	DialFailed
	HandshakeFailed
	Timeout
	outcomeCount
)

// Token is an in-memory consent generation. It is never serialized.
type Token struct{ generation uint64 }

// Collector's zero value is disabled. No unbounded queues or per-peer maps exist.
type Collector struct {
	mu         sync.Mutex
	enabled    bool
	generation uint64
	outcomes   [kindCount][outcomeCount]uint16
	latency    [kindCount][4]uint16
}

// SetEnabled also clears the window and invalidates in-flight observations.
func (c *Collector) SetEnabled(enabled bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.generation++
	c.enabled = enabled
	c.outcomes = [kindCount][outcomeCount]uint16{}
	c.latency = [kindCount][4]uint16{}
}

func (c *Collector) Begin() Token {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.enabled {
		return Token{}
	}
	return Token{c.generation}
}

func (t Token) Active() bool { return t.generation != 0 }

func (c *Collector) Finish(token Token, kind Kind, outcome Outcome, elapsed time.Duration) {
	if !token.Active() || kind >= kindCount || outcome >= outcomeCount {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.enabled || token.generation != c.generation {
		return
	}
	increment(&c.outcomes[kind][outcome])
	if elapsed < 0 {
		return
	}
	bucket := 0
	switch {
	case elapsed >= time.Minute:
		bucket = 3
	case elapsed >= 10*time.Second:
		bucket = 2
	case elapsed >= time.Second:
		bucket = 1
	}
	increment(&c.latency[kind][bucket])
}

func increment(value *uint16) {
	if *value < 101 {
		*value++
	}
}

func countBucket(value uint16) string {
	switch {
	case value == 0:
		return "0"
	case value == 1:
		return "1"
	case value <= 5:
		return "2-5"
	case value <= 20:
		return "6-20"
	case value <= 100:
		return "21-100"
	default:
		return "101+"
	}
}

type Outcomes struct {
	Success         string `json:"success"`
	Rejected        string `json:"rejected"`
	DialFailed      string `json:"dial_failed"`
	HandshakeFailed string `json:"handshake_failed"`
	Timeout         string `json:"timeout"`
}
type Latency struct {
	Under1s     string `json:"under_1s"`
	From1To10s  string `json:"1_to_10s"`
	From10To60s string `json:"10_to_60s"`
	AtLeast60s  string `json:"60s_plus"`
}
type TransportSummary struct {
	Transport string   `json:"transport"`
	Outcomes  Outcomes `json:"outcomes"`
	Latency   Latency  `json:"latency"`
}
type Snapshot struct {
	SchemaVersion int                         `json:"schema_version"`
	Enabled       bool                        `json:"enabled"`
	Outbound      [kindCount]TransportSummary `json:"outbound"`
}

func (c *Collector) Snapshot() Snapshot {
	c.mu.Lock()
	defer c.mu.Unlock()
	s := Snapshot{SchemaVersion: 1, Enabled: c.enabled}
	for i, name := range [...]string{"direct", "tor", "yggdrasil", "mixed", "unknown"} {
		o, l := c.outcomes[i], c.latency[i]
		s.Outbound[i] = TransportSummary{
			Transport: name,
			Outcomes:  Outcomes{countBucket(o[0]), countBucket(o[1]), countBucket(o[2]), countBucket(o[3]), countBucket(o[4])},
			Latency:   Latency{countBucket(l[0]), countBucket(l[1]), countBucket(l[2]), countBucket(l[3])},
		}
	}
	return s
}
