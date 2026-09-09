package main

import (
	"context"
	"fmt"
	"log"
	"math/rand"
	"sync"
	"time"
)

// runNetworkFlap periodically kills and restarts nodes to simulate network flaps.
func runNetworkFlap(ctx context.Context, nodes []*Node, flapRate float64) {
	if flapRate <= 0 || flapRate > 1 {
		log.Printf("[network] invalid flap-rate %f, skipping", flapRate)
		return
	}

	log.Printf("[network] starting flap simulator: rate=%.2f/min, nodes=%d",
		flapRate, len(nodes))

	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()

	var mu sync.Mutex
	downNodes := make(map[int]bool)

	for {
		select {
		case <-ctx.Done():
			mu.Lock()
			for id := range downNodes {
				log.Printf("[network] restoring node %d on shutdown", id)
				_ = restoreNode(nodes, id)
			}
			mu.Unlock()
			log.Printf("[network] flap simulator stopped")
			return

		case <-ticker.C:
			if rand.Float64() >= flapRate {
				continue
			}

			mu.Lock()
			candidates := make([]int, 0, len(nodes))
			for i := range nodes {
				if !downNodes[i] {
					candidates = append(candidates, i)
				}
			}
			if len(candidates) < 2 {
				mu.Unlock()
				continue
			}
			victimIdx := candidates[rand.Intn(len(candidates))]
			downNodes[victimIdx] = true
			mu.Unlock()

			log.Printf("[network] killing node %d (%s)", victimIdx, nodes[victimIdx].Alias)
			killNode(nodes, victimIdx)

			downDuration := time.Duration(5+rand.Intn(25)) * time.Second

			go func(idx int, dur time.Duration) {
				select {
				case <-ctx.Done():
					return
				case <-time.After(dur):
					log.Printf("[network] restoring node %d after %s", idx, dur)
					if err := restoreNode(nodes, idx); err != nil {
						log.Printf("[network] restore failed for node %d: %v", idx, err)
						return
					}
					mu.Lock()
					delete(downNodes, idx)
					mu.Unlock()
				}
			}(victimIdx, downDuration)
		}
	}
}

func killNode(nodes []*Node, idx int) {
	if idx < 0 || idx >= len(nodes) {
		return
	}
	n := nodes[idx]
	if n == nil || n.Manager == nil {
		return
	}

	log.Printf("[network] kill node %d (closing listener and sessions)", idx)
	n.Close()
	n.Manager = nil
}

func restoreNode(nodes []*Node, idx int) error {
	if idx < 0 || idx >= len(nodes) {
		return fmt.Errorf("node index out of range: %d", idx)
	}
	n := nodes[idx]
	if n == nil {
		return fmt.Errorf("nil node at index %d", idx)
	}

	restored, err := createNodeWithID(context.Background(), idx, n.Dir)
	if err != nil {
		return err
	}
	nodes[idx] = restored

	// Reconnect with all other live nodes
	for i, peer := range nodes {
		if i == idx || peer == nil || peer.Manager == nil {
			continue
		}
		_ = restored.Manager.ConnectPeer(peer.Address, peer.Fingerprint())
	}

	return nil
}

func runClockSkew(ctx context.Context, nodes []*Node, skewFraction float64) {
	if skewFraction <= 0 {
		return
	}

	count := int(float64(len(nodes)) * skewFraction)
	if count == 0 {
		return
	}

	log.Printf("[clock-skew] simulated skew active on %d/%d nodes", count, len(nodes))
}

func runNetworkPartition(ctx context.Context, nodes []*Node, partitionDuration time.Duration) {
	log.Printf("[partition] starting %s partition", partitionDuration)

	if len(nodes) < 4 {
		log.Printf("[partition] need at least 4 nodes, skipping")
		return
	}

	mid := len(nodes) / 2
	groupA := nodes[:mid]
	groupB := nodes[mid:]

	for _, a := range groupA {
		for _, b := range groupB {
			disconnectPair(a, b)
		}
	}

	log.Printf("[partition] isolated %d nodes from %d nodes for %s",
		len(groupA), len(groupB), partitionDuration)

	select {
	case <-ctx.Done():
	case <-time.After(partitionDuration):
	}

	log.Printf("[partition] restoring connections")
	for _, a := range groupA {
		for _, b := range groupB {
			reconnectPair(a, b)
		}
	}
}

func disconnectPair(a, b *Node) {
	if a == nil || b == nil || a.Manager == nil || b.Manager == nil {
		return
	}
	if nm := a.Manager.GetNetManager(); nm != nil {
		if sess := nm.GetSession(b.Fingerprint()); sess != nil {
			_ = sess.Close()
		}
	}
	if nm := b.Manager.GetNetManager(); nm != nil {
		if sess := nm.GetSession(a.Fingerprint()); sess != nil {
			_ = sess.Close()
		}
	}
}

func reconnectPair(a, b *Node) {
	if a == nil || b == nil || a.Manager == nil || b.Manager == nil {
		return
	}
	_ = a.Manager.ConnectPeer(b.Address, b.Fingerprint())
}
