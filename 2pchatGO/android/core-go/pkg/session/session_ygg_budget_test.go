package session

import (
	"testing"
	"time"
)

// ackBudgetTotal reproduces the wait schedule of sendReliablePlaintext: the
// first attempt waits ackTimeout and each subsequent retry multiplies the
// delay by 1.5.
func ackBudgetTotal(ackTimeout time.Duration, maxRetries int) time.Duration {
	var total time.Duration
	delay := ackTimeout
	for attempt := 0; attempt <= maxRetries; attempt++ {
		total += delay
		delay = time.Duration(float64(delay) * 1.5)
	}
	return total
}

// TestYggdrasilAckBudgetSurvivesMeshRouteGap pins the liveness contract for
// Yggdrasil sessions: a transient mesh route gap (device sleep, Doze, route
// flap) blocks ACKs for minutes. The total ACK budget must outlast such a
// gap, otherwise every transient flap tears down an authenticated session
// after ~62s (8s timeout x 3 retries) and the peer flips to offline with no
// visible reason. The user-space shim retransmits unacknowledged segments
// indefinitely, so extending this budget costs nothing while the route is
// down and lets in-flight frames land when the route recovers.
func TestYggdrasilAckBudgetSurvivesMeshRouteGap(t *testing.T) {
	total := ackBudgetTotal(YggdrasilAckTimeout, YggdrasilMaxRetries)
	const minTolerance = 5 * time.Minute
	if total < minTolerance {
		t.Fatalf("yggdrasil ACK budget total %v is below the %v mesh route-gap tolerance (session dies on a transient flap)", total, minTolerance)
	}
	// A single attempt must cover a slow route rebuild; a 5s-style budget
	// cannot.
	if YggdrasilAckTimeout < 10*time.Second {
		t.Fatalf("yggdrasil per-attempt ACK timeout %v is too short for a mesh route rebuild", YggdrasilAckTimeout)
	}
	// Yggdrasil must be strictly more tolerant than direct TCP, which dies
	// fast on purpose (a dead direct socket is not recovering).
	directTotal := ackBudgetTotal(DefaultAckTimeout, DefaultMaxRetries)
	if total <= directTotal {
		t.Fatalf("yggdrasil ACK budget %v must exceed direct budget %v", total, directTotal)
	}
}
