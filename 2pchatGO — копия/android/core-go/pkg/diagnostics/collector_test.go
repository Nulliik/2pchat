package diagnostics

import (
	"encoding/json"
	"os"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestAndroidSnapshotContract(t *testing.T) {
	var c Collector
	c.SetEnabled(true)
	fixture, err := os.ReadFile("testdata/core_snapshot_v1.json")
	if err != nil {
		t.Fatal(err)
	}
	encoded, err := json.Marshal(c.Snapshot())
	if err != nil {
		t.Fatal(err)
	}
	var expected, actual any
	if err := json.Unmarshal(fixture, &expected); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(encoded, &actual); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(expected, actual) {
		t.Fatal("native snapshot changed from the shared Android contract fixture")
	}
}

func TestConsentAndResetDiscardInFlightObservations(t *testing.T) {
	var c Collector
	beforeConsent := c.Begin()
	c.SetEnabled(true)
	c.Finish(beforeConsent, Direct, Success, time.Second)
	beforeReset := c.Begin()
	c.SetEnabled(true) // Clear starts a new collection window.
	c.Finish(beforeReset, Direct, Success, time.Second)
	beforeDisable := c.Begin()
	c.SetEnabled(false)
	c.SetEnabled(true)
	c.Finish(beforeDisable, Direct, Success, time.Second)
	if got := c.Snapshot().Outbound[0].Outcomes.Success; got != "0" {
		t.Fatalf("observations crossed a consent/reset boundary: %s", got)
	}
	c.Finish(c.Begin(), Direct, Success, time.Second)
	if got := c.Snapshot().Outbound[0].Outcomes.Success; got != "1" {
		t.Fatalf("opted-in observation missing: %s", got)
	}
	c.SetEnabled(false)
	if s := c.Snapshot(); s.Enabled || s.Outbound[0].Outcomes.Success != "0" {
		t.Fatal("disable must erase counters")
	}
}

func TestSaturationLatencyAndFixedSchema(t *testing.T) {
	var c Collector
	c.SetEnabled(true)
	for i := 0; i < 100000; i++ {
		c.Finish(c.Begin(), Tor, Timeout, time.Minute)
	}
	c.Finish(c.Begin(), Kind(255), Outcome(255), -time.Second)
	s := c.Snapshot()
	if s.Outbound[1].Outcomes.Timeout != "101+" || s.Outbound[1].Latency.AtLeast60s != "101+" {
		t.Fatalf("unexpected saturated counters: %+v", s.Outbound[1])
	}
	encoded, err := json.Marshal(s)
	if err != nil || len(encoded) > 4096 {
		t.Fatalf("unbounded or invalid snapshot: %d, %v", len(encoded), err)
	}
	for _, forbidden := range []string{"timestamp", "endpoint", "fingerprint", "session_id", "message", "generation"} {
		if strings.Contains(string(encoded), forbidden) {
			t.Fatalf("unexpected field %q in snapshot", forbidden)
		}
	}
	for _, tc := range []struct {
		count uint16
		want  string
	}{
		{0, "0"}, {1, "1"}, {2, "2-5"}, {5, "2-5"}, {6, "6-20"},
		{20, "6-20"}, {21, "21-100"}, {100, "21-100"}, {101, "101+"},
	} {
		if got := countBucket(tc.count); got != tc.want {
			t.Errorf("bucket(%d) = %q, want %q", tc.count, got, tc.want)
		}
	}
}

func TestConcurrentCollectSnapshotAndConsent(t *testing.T) {
	var c Collector
	c.SetEnabled(true)
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 1000; j++ {
				c.Finish(c.Begin(), Yggdrasil, Success, time.Second)
				_ = c.Snapshot()
				if j%10 == 0 {
					c.SetEnabled(j%20 == 0)
				}
			}
		}()
	}
	wg.Wait()
	c.SetEnabled(false)
	for _, row := range c.Snapshot().Outbound {
		if row.Outcomes.Success != "0" {
			t.Fatal("counter survived disable")
		}
	}
}
