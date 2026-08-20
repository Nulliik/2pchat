package discovery

import (
	"context"
	"testing"
	"time"
)

func TestLiveResolveNullPeer(t *testing.T) {
	svc := NewDiscoveryService(
		"test_fingerprint",
		50001,
		nil,
		false,
		nil,
	)

	trackers := []string{
		"udp://tracker.torrent.eu.org:451/announce",
		"udp://open.stealth.si:80/announce",
		"udp://tracker.opentrackr.org:1337/announce",
		"udp://tracker2.dler.org:80/announce",
		"http://tracker.opentrackr.org:1337/announce",
		"http://tracker2.dler.org:80/announce",
		"http://tracker.qu.ax:6969/announce",
		"https://tracker.opentrackr.org:443/announce",
		"https://tr.nyacat.pw:443/announce",
	}
	svc.SetTrackers(trackers)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	peers, err := svc.ResolvePeers(ctx, "Null", "36571c05")
	if err != nil {
		t.Fatalf("ResolvePeers failed: %v", err)
	}

	t.Logf("Discovered %d endpoints from live trackers for Null#36571c05:", len(peers))
	for _, p := range peers {
		t.Logf("  - %s (IP=%s, Port=%d)", p.String(), p.IP, p.Port)
	}

	if len(peers) == 0 {
		t.Log("Note: No live peers currently announced on public trackers or network timeout.")
	} else {
		t.Logf("✅ Successfully discovered %d endpoints for Null#36571c05 in Go!", len(peers))
	}
}
