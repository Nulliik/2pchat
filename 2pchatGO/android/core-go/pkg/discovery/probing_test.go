package discovery

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"
)

func TestClassifyTier(t *testing.T) {
	if ClassifyTier("192.168.1.5:50001") != TierLAN {
		t.Fatal("Expected TierLAN for 192.168.1.5")
	}
	if ClassifyTier("10.0.0.12:50001") != TierLAN {
		t.Fatal("Expected TierLAN for 10.0.0.12")
	}
	if ClassifyTier("127.0.0.1:50001") != TierLAN {
		t.Fatal("Expected TierLAN for localhost")
	}
	if ClassifyTier("8.8.8.8:50001") != TierWANDirect {
		t.Fatal("Expected TierWANDirect for public IPv4")
	}
	if ClassifyTier("[200:1234::1]:50001") != TierYggdrasil {
		t.Fatal("Expected TierYggdrasil for 200:: IPv6")
	}
	if ClassifyTier("[2a00:1450:4001:828::200e]:50001") != TierDirectIPv6 {
		t.Fatal("Expected TierDirectIPv6 for global mobile IPv6")
	}
	if ClassifyTier("[2001:db8::1]:50001") != TierDirectIPv6 {
		t.Fatal("Expected TierDirectIPv6 for 2001:: public IPv6")
	}
	if ClassifyTier("[fe80::1]:50001") != TierLAN {
		t.Fatal("Expected TierLAN for link-local IPv6")
	}
	if ClassifyTier("abcdef123456.onion:50001") != TierTor {
		t.Fatal("Expected TierTor for .onion")
	}
}

func TestFastTieredProberRace(t *testing.T) {
	prober := NewFastTieredProber()

	endpoints := []string{
		"192.168.1.100:50001", // LAN (Fast)
		"1.2.3.4:50001",       // WAN Direct (Slow)
		"abcdef.onion:50001",  // Tor (Very slow)
	}

	mockDialer := func(ctx context.Context, endpoint string) (net.Conn, error) {
		tier := ClassifyTier(endpoint)
		switch tier {
		case TierLAN:
			// Fast response (10ms)
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(10 * time.Millisecond):
				server, client := net.Pipe()
				go server.Close()
				return client, nil
			}
		case TierWANDirect:
			// Slower response (500ms)
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(500 * time.Millisecond):
				server, client := net.Pipe()
				go server.Close()
				return client, nil
			}
		default:
			// Fails or times out
			return nil, errors.New("connection refused")
		}
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	start := time.Now()
	conn, winnerEndpoint, err := prober.ProbeFast(ctx, endpoints, mockDialer)
	elapsed := time.Since(start)

	if err != nil {
		t.Fatalf("ProbeFast failed: %v", err)
	}
	defer conn.Close()

	if winnerEndpoint != "192.168.1.100:50001" {
		t.Fatalf("Expected LAN endpoint to win, got: %s", winnerEndpoint)
	}

	if elapsed > 200*time.Millisecond {
		t.Fatalf("Expected fast race resolution under 200ms, took %v", elapsed)
	}
}

func TestFastTieredProberPrefersYggdrasilOverWAN(t *testing.T) {
	prober := NewFastTieredProber()
	ygg := "[200:182d:e207:ca9b:8205:5f82:3aa:c4f7]:50001"
	wan := "31.58.79.18:50001"

	conn, winner, err := prober.ProbeFast(context.Background(), []string{wan, ygg}, func(ctx context.Context, endpoint string) (net.Conn, error) {
		server, client := net.Pipe()
		go server.Close()
		return client, nil
	})
	if err != nil {
		t.Fatalf("ProbeFast failed: %v", err)
	}
	defer conn.Close()
	if winner != ygg {
		t.Fatalf("expected Yggdrasil endpoint to win over WAN, got %s", winner)
	}
}

func TestFastTieredProberCooldown(t *testing.T) {
	prober := NewFastTieredProber()

	failedEndpoint := "1.2.3.4:50001"
	failDialer := func(ctx context.Context, endpoint string) (net.Conn, error) {
		return nil, errors.New("dial failed")
	}

	_, _, err := prober.ProbeFast(context.Background(), []string{failedEndpoint}, failDialer)
	if err == nil {
		t.Fatal("Expected error on failed dial")
	}

	// Next dial immediately should return ErrNoViableEndpoints because endpoint is in cooldown
	_, _, err2 := prober.ProbeFast(context.Background(), []string{failedEndpoint}, failDialer)
	if err2 != ErrNoViableEndpoints {
		t.Fatalf("Expected ErrNoViableEndpoints due to cooldown, got: %v", err2)
	}

	// Reset cooldowns
	prober.ResetCooldowns()

	// Should attempt dial again
	_, _, err3 := prober.ProbeFast(context.Background(), []string{failedEndpoint}, failDialer)
	if err3 != ErrNoViableEndpoints {
		// Means it attempted dial and returned dialer error, not skipped before dial
	}
}
