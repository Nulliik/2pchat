package main

import (
	"context"
	"crypto/sha256"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"

	"twopchat/core/pkg/bridge"
	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/session"
	"twopchat/core/pkg/transport"
)

// Node represents an isolated 2PChat Core instance running locally.
type Node struct {
	ID        int
	Alias     string
	Dir       string
	Manager   *bridge.SessionManager
	Address   string // 127.0.0.1:port
	Seed      [32]byte
	PrivKey   *crypto.IdentityKeyPair

	sentCount  atomic.Int64
	recvCount  atomic.Int64
	dupCount   atomic.Int64
	seenMsgIDs sync.Map // map[string]struct{}

	mu       sync.RWMutex
	sessions map[string]*session.Session // peerFP -> session
}

func createNodes(ctx context.Context, count int) ([]*Node, error) {
	nodes := make([]*Node, 0, count)

	for i := 0; i < count; i++ {
		node, err := createNode(ctx, i)
		if err != nil {
			cleanupNodes(nodes)
			return nil, fmt.Errorf("node %d: %w", i, err)
		}
		nodes = append(nodes, node)
	}

	return nodes, nil
}

func createNode(ctx context.Context, id int) (*Node, error) {
	// Deterministic identity seed: SHA-256("node-<id>")
	seed := sha256.Sum256([]byte(fmt.Sprintf("node-%d", id)))

	dir, err := os.MkdirTemp("", fmt.Sprintf("soak-node-%d-", id))
	if err != nil {
		return nil, err
	}

	return createNodeFromSeedAndDir(ctx, id, seed, dir)
}

func createNodeWithID(ctx context.Context, id int, existingDir string) (*Node, error) {
	seed := sha256.Sum256([]byte(fmt.Sprintf("node-%d", id)))
	if existingDir == "" {
		var err error
		existingDir, err = os.MkdirTemp("", fmt.Sprintf("soak-node-%d-", id))
		if err != nil {
			return nil, err
		}
	}
	return createNodeFromSeedAndDir(ctx, id, seed, existingDir)
}

func createNodeFromSeedAndDir(ctx context.Context, id int, seed [32]byte, dir string) (*Node, error) {
	privKey, err := crypto.IdentityKeyPairFromSeed(seed[:])
	if err != nil {
		_ = os.RemoveAll(dir)
		return nil, fmt.Errorf("derive identity: %w", err)
	}

	// Write identity file before Init so SessionManager loads the deterministic identity
	keyData := make([]byte, 96)
	copy(keyData[:32], privKey.Private.Bytes())
	copy(keyData[32:96], privKey.Signing)
	if err := os.WriteFile(filepath.Join(dir, "identity_v1.key"), keyData, 0600); err != nil {
		_ = os.RemoveAll(dir)
		return nil, fmt.Errorf("write identity key: %w", err)
	}
	crypto.Zeroize(keyData)

	node := &Node{
		ID:       id,
		Alias:    fmt.Sprintf("node%d", id),
		Dir:      dir,
		Seed:     seed,
		PrivKey:  privKey,
		sessions: make(map[string]*session.Session),
	}

	mgr := &bridge.SessionManager{}
	mgr.SetStorageDir(dir)
	mgr.SetNickname(node.Alias)
	mgr.ApplyPolicy(transport.PolicySpeed)

	// Configure callbacks for metrics tracking
	cb := session.EventCallbacks{
		OnMessageReceived: func(peerFP string, payload []byte, msgID string) {
			node.recvCount.Add(1)
			if _, loaded := node.seenMsgIDs.LoadOrStore(msgID, struct{}{}); loaded {
				node.dupCount.Add(1)
			}
		},
	}
	mgr.SetCallbacks(cb, nil)

	if err := mgr.Init(); err != nil {
		_ = os.RemoveAll(dir)
		return nil, fmt.Errorf("init manager: %w", err)
	}

	// Start listener on OS-allocated loopback port
	if err := mgr.StartListener(0); err != nil {
		_ = mgr.Close()
		_ = os.RemoveAll(dir)
		return nil, fmt.Errorf("start listener: %w", err)
	}

	port := mgr.GetBoundPort()
	if port <= 0 {
		_ = mgr.Close()
		_ = os.RemoveAll(dir)
		return nil, fmt.Errorf("invalid bound port: %d", port)
	}

	node.Manager = mgr
	node.Address = fmt.Sprintf("127.0.0.1:%d", port)
	return node, nil
}

func cleanupNodes(nodes []*Node) {
	for _, n := range nodes {
		if n != nil {
			n.Close()
			if n.Dir != "" {
				_ = os.RemoveAll(n.Dir)
			}
		}
	}
}

func (n *Node) Close() {
	if n.Manager != nil {
		_ = n.Manager.Close()
	}
}

func (n *Node) Fingerprint() string {
	if n.Manager != nil {
		return n.Manager.GetLocalFingerprint()
	}
	if n.PrivKey != nil {
		return crypto.Fingerprint(n.PrivKey.Public.Bytes())
	}
	return ""
}

func (n *Node) GetSigningPublicKey() string {
	if n.Manager != nil {
		pk, _ := n.Manager.GetLocalSigningPublicKey()
		return pk
	}
	return ""
}

func (n *Node) SendMessage(ctx context.Context, peerFP, text string) error {
	if n.Manager == nil {
		return fmt.Errorf("node %d manager is closed", n.ID)
	}
	msgID, err := n.Manager.SendMessage(peerFP, text)
	if err != nil {
		return err
	}
	n.sentCount.Add(1)
	n.seenMsgIDs.LoadOrStore(msgID, struct{}{})
	return nil
}

func (n *Node) GetSession(peerFP string) *session.Session {
	n.mu.RLock()
	defer n.mu.RUnlock()
	return n.sessions[peerFP]
}

func (n *Node) SetSession(peerFP string, s *session.Session) {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.sessions[peerFP] = s
}
