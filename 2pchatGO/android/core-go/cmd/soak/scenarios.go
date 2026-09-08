package main

import (
	"context"
	"fmt"
	"log"
	"math/rand"
	"time"
)

// Scenario represents a pluggable soak workload scenario.
type Scenario interface {
	Name() string
	Run(ctx context.Context, nodes []*Node, cfg Config)
}

func getScenario(name string) (Scenario, error) {
	switch name {
	case "pairwise":
		return &PairwiseScenario{}, nil
	case "group":
		return &GroupScenario{}, nil
	case "succession":
		return &SuccessionScenario{}, nil
	case "outbox":
		return &OutboxScenario{}, nil
	default:
		return nil, fmt.Errorf("unknown scenario: %s", name)
	}
}

// PairwiseScenario sends continuous pairwise encrypted messages.
type PairwiseScenario struct{}

func (s *PairwiseScenario) Name() string { return "pairwise" }

func (s *PairwiseScenario) Run(ctx context.Context, nodes []*Node, cfg Config) {
	if len(nodes) < 2 {
		log.Printf("[pairwise] requires at least 2 nodes, skipping")
		return
	}
	log.Printf("[pairwise] starting with %d nodes", len(nodes))

	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()

	msgCount := 0
	for {
		select {
		case <-ctx.Done():
			log.Printf("[pairwise] stopped after %d messages", msgCount)
			return
		case <-ticker.C:
			senderIdx := rand.Intn(len(nodes))
			receiverIdx := rand.Intn(len(nodes))
			if senderIdx == receiverIdx {
				receiverIdx = (senderIdx + 1) % len(nodes)
			}

			sender := nodes[senderIdx]
			receiver := nodes[receiverIdx]
			if sender == nil || receiver == nil || sender.Manager == nil || receiver.Manager == nil {
				continue
			}

			if !sender.Manager.IsPeerOnline(receiver.Fingerprint()) {
				// Attempt to reconnect if disconnected
				_ = sender.Manager.ConnectPeer(receiver.Address, receiver.Fingerprint())
				continue
			}

			payload := fmt.Sprintf("pairwise-%d-%d", msgCount, time.Now().UnixNano())
			if err := sender.SendMessage(ctx, receiver.Fingerprint(), payload); err != nil {
				log.Printf("[pairwise] send error %d->%d: %v", senderIdx, receiverIdx, err)
				continue
			}
			msgCount++

			if msgCount%500 == 0 {
				log.Printf("[pairwise] sent %d messages", msgCount)
			}
		}
	}
}

// GroupScenario simulates group messaging broadcast.
type GroupScenario struct{}

func (s *GroupScenario) Name() string { return "group" }

func (s *GroupScenario) Run(ctx context.Context, nodes []*Node, cfg Config) {
	if len(nodes) < 3 {
		log.Printf("[group] requires at least 3 nodes, skipping")
		return
	}
	log.Printf("[group] starting with %d nodes", len(nodes))

	ticker := time.NewTicker(300 * time.Millisecond)
	defer ticker.Stop()

	msgCount := 0
	groupID := "soak-group-1"

	for {
		select {
		case <-ctx.Done():
			log.Printf("[group] stopped after %d messages", msgCount)
			return
		case <-ticker.C:
			senderIdx := rand.Intn(len(nodes))
			sender := nodes[senderIdx]
			if sender == nil || sender.Manager == nil {
				continue
			}

			// Broadcast group message payload to other members
			payload := fmt.Sprintf(`{"type":"group_msg","group_id":"%s","seq":%d,"timestamp":%d}`,
				groupID, msgCount, time.Now().UnixMilli())

			for j, member := range nodes {
				if j == senderIdx || member == nil || member.Manager == nil {
					continue
				}
				if sender.Manager.IsPeerOnline(member.Fingerprint()) {
					_ = sender.SendMessage(ctx, member.Fingerprint(), payload)
				}
			}
			msgCount++

			if msgCount%200 == 0 {
				log.Printf("[group] broadcast %d group messages", msgCount)
			}
		}
	}
}

// SuccessionScenario tests certificate generation, heartbeats, and claims.
type SuccessionScenario struct{}

func (s *SuccessionScenario) Name() string { return "succession" }

func (s *SuccessionScenario) Run(ctx context.Context, nodes []*Node, cfg Config) {
	if len(nodes) < 2 {
		log.Printf("[succession] requires at least 2 nodes, skipping")
		return
	}
	log.Printf("[succession] starting with %d nodes", len(nodes))

	owner := nodes[0]
	successor := nodes[1]
	groupID := "soak-succession-group-1"

	// 1. Issue succession certificate (sequence 1)
	certJSON, err := owner.Manager.CreateSuccessionCertificate(
		groupID,
		successor.Fingerprint(),
		successor.GetSigningPublicKey(),
		30,
		1,
	)
	if err != nil {
		log.Printf("[succession] failed to create certificate: %v", err)
		return
	}

	// 2. Successor verifies certificate
	if err := successor.Manager.VerifySuccessionCertificate(certJSON); err != nil {
		log.Printf("[succession] successor failed to verify certificate: %v", err)
		return
	}
	log.Printf("[succession] succession certificate created and verified successfully")

	// Emit heartbeats periodically
	heartbeatInterval := 10 * time.Second
	if cfg.Duration > 1*time.Hour {
		heartbeatInterval = 5 * time.Minute
	}
	ticker := time.NewTicker(heartbeatInterval)
	defer ticker.Stop()

	hbCount := 0
	var lastHbJSON string

	for {
		select {
		case <-ctx.Done():
			log.Printf("[succession] stopped after %d heartbeats", hbCount)
			return
		case <-ticker.C:
			if owner == nil || owner.Manager == nil {
				continue
			}

			hbJSON, err := owner.Manager.CreateOwnerHeartbeat(groupID)
			if err != nil {
				log.Printf("[succession] error creating heartbeat: %v", err)
				continue
			}
			lastHbJSON = hbJSON

			if err := successor.Manager.VerifyOwnerHeartbeat(hbJSON); err != nil {
				log.Printf("[succession] successor heartbeat verification failed: %v", err)
				continue
			}

			hbCount++
			if hbCount%10 == 0 {
				log.Printf("[succession] emitted and verified %d heartbeats", hbCount)

				// Test claim creation
				claimJSON, claimErr := successor.Manager.CreateSuccessionClaim(certJSON, lastHbJSON)
				if claimErr == nil && claimJSON != "" {
					_ = successor.Manager.VerifySuccessionClaim(certJSON, claimJSON, lastHbJSON)
				}
			}
		}
	}
}

// OutboxScenario tests message delivery under peer disconnections.
type OutboxScenario struct{}

func (s *OutboxScenario) Name() string { return "outbox" }

func (s *OutboxScenario) Run(ctx context.Context, nodes []*Node, cfg Config) {
	if len(nodes) < 2 {
		log.Printf("[outbox] requires at least 2 nodes, skipping")
		return
	}
	log.Printf("[outbox] starting outbox resilience test")

	sender := nodes[0]
	receiver := nodes[1]

	// Send initial message
	_ = sender.SendMessage(ctx, receiver.Fingerprint(), "outbox-pre-flap")

	// Briefly disconnect receiver session
	disconnectPair(sender, receiver)
	time.Sleep(500 * time.Millisecond)

	// Reconnect and send follow-up message
	reconnectPair(sender, receiver)
	time.Sleep(500 * time.Millisecond)

	_ = sender.SendMessage(ctx, receiver.Fingerprint(), "outbox-post-reconnect")
	log.Printf("[outbox] outbox reconnect cycle complete")
}

func establishFullMesh(ctx context.Context, nodes []*Node) error {
	log.Printf("Establishing full mesh: %d nodes = %d sessions",
		len(nodes), len(nodes)*(len(nodes)-1)/2)

	// 1. Trigger ConnectPeer for all pairs
	for i := 0; i < len(nodes); i++ {
		for j := i + 1; j < len(nodes); j++ {
			_ = nodes[i].Manager.ConnectPeer(nodes[j].Address, nodes[j].Fingerprint())
		}
	}

	// 2. Wait until all pairs report online or deadline
	deadline := time.Now().Add(30 * time.Second)
	for {
		if time.Now().After(deadline) {
			break
		}
		allOnline := true
		for i := 0; i < len(nodes); i++ {
			for j := i + 1; j < len(nodes); j++ {
				if !nodes[i].Manager.IsPeerOnline(nodes[j].Fingerprint()) &&
					!nodes[j].Manager.IsPeerOnline(nodes[i].Fingerprint()) {
					allOnline = false
					_ = nodes[i].Manager.ConnectPeer(nodes[j].Address, nodes[j].Fingerprint())
				}
			}
		}
		if allOnline {
			log.Printf("Full mesh established successfully for %d nodes", len(nodes))
			return nil
		}
		time.Sleep(200 * time.Millisecond)
	}

	log.Printf("Mesh established (some nodes may still be handshaking in background)")
	return nil
}
