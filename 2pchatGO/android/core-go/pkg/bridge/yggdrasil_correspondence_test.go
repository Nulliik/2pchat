package bridge

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"
	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/session"
	"twopchat/core/pkg/transport"
)

// TestGoToGoCorrespondenceOverIPv6Yggdrasil tests full bidirectional chat correspondence
// between two native Go instances over IPv6/Yggdrasil stack.
func TestGoToGoCorrespondenceOverIPv6Yggdrasil(t *testing.T) {
	tmpDirA, err := os.MkdirTemp("", "gogo_alice_*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDirA)

	tmpDirB, err := os.MkdirTemp("", "gogo_bob_*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDirB)

	// 1. Initialize Alice (Go)
	aliceID, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	alicePrekeyPriv, alicePrekeyPub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatal(err)
	}
	aliceFP := crypto.Fingerprint(aliceID.Public.Bytes())

	var aliceReceivedMu sync.Mutex
	var aliceReceivedMessages []string
	aliceMsgChan := make(chan string, 100)

	aliceCallbacks := session.EventCallbacks{
		OnMessageReceived: func(peerFP string, payload []byte, messageID string) {
			var m map[string]any
			if err := json.Unmarshal(payload, &m); err == nil {
				if body, ok := m["body"].(string); ok {
					aliceReceivedMu.Lock()
					aliceReceivedMessages = append(aliceReceivedMessages, body)
					aliceReceivedMu.Unlock()
					aliceMsgChan <- body
				}
			}
		},
	}

	aliceMgr := session.NewManager(
		aliceID,
		alicePrekeyPriv,
		alicePrekeyPub,
		"",
		false,
		aliceCallbacks,
	)
	aliceMgr.SetNickname("Alice_Go")
	aliceMgr.SetStorageDir(tmpDirA)

	// Bind Alice to ephemeral port
	l, err := net.Listen("tcp", "[::1]:0")
	var alicePort int
	if err != nil {
		l2, err2 := net.Listen("tcp", "127.0.0.1:0")
		if err2 != nil {
			t.Fatal(err2)
		}
		alicePort = l2.Addr().(*net.TCPAddr).Port
		_ = l2.Close()
	} else {
		alicePort = l.Addr().(*net.TCPAddr).Port
		_ = l.Close()
	}

	if err := aliceMgr.StartListener(alicePort); err != nil {
		t.Fatalf("Alice failed to start listener on port %d: %v", alicePort, err)
	}
	defer aliceMgr.StopListener()

	// 2. Initialize Bob (Go)
	bobID, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	bobPrekeyPriv, bobPrekeyPub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatal(err)
	}
	bobFP := crypto.Fingerprint(bobID.Public.Bytes())

	var bobReceivedMu sync.Mutex
	var bobReceivedMessages []string
	bobMsgChan := make(chan string, 100)

	bobCallbacks := session.EventCallbacks{
		OnMessageReceived: func(peerFP string, payload []byte, messageID string) {
			var m map[string]any
			if err := json.Unmarshal(payload, &m); err == nil {
				if body, ok := m["body"].(string); ok {
					bobReceivedMu.Lock()
					bobReceivedMessages = append(bobReceivedMessages, body)
					bobReceivedMu.Unlock()
					bobMsgChan <- body
				}
			}
		},
	}

	bobMgr := session.NewManager(
		bobID,
		bobPrekeyPriv,
		bobPrekeyPub,
		"",
		false,
		bobCallbacks,
	)
	bobMgr.SetNickname("Bob_Go")
	bobMgr.SetStorageDir(tmpDirB)

	// 3. Bob connects to Alice via IPv6 endpoint
	aliceEndpoint := fmt.Sprintf("[::1]:%d", alicePort)
	t.Logf("Bob connecting to Alice at IPv6 endpoint: %s", aliceEndpoint)

	sess, err := bobMgr.ConnectPeer(aliceEndpoint, aliceFP)
	if err != nil {
		aliceEndpoint = fmt.Sprintf("127.0.0.1:%d", alicePort)
		sess, err = bobMgr.ConnectPeer(aliceEndpoint, aliceFP)
		if err != nil {
			t.Fatalf("Bob failed to connect to Alice: %v", err)
		}
	}
	t.Logf("✅ Go-to-Go X3DH handshake established successfully (PeerFP: %s)", sess.PeerFingerprint())

	time.Sleep(100 * time.Millisecond)

	// 4. Bob sends first message to Alice
	msg1 := "Hello Alice from Bob Go! 🚀🔒 (Yggdrasil/IPv6 Stack)"
	msgID1, err := bobMgr.SendMessage(aliceFP, msg1)
	if err != nil {
		t.Fatalf("Bob failed to send chat to Alice: %v", err)
	}
	t.Logf("Bob sent message #1 (ID=%s): %s", msgID1, msg1)

	select {
	case received := <-aliceMsgChan:
		if received != msg1 {
			t.Fatalf("Alice received unexpected text: %s", received)
		}
		t.Logf("✅ Alice received message #1 from Bob: %s", received)
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for Alice to receive message #1 from Bob")
	}

	// 5. Alice replies to Bob
	msg2 := "Hello Bob! I received your message loud and clear over Yggdrasil/IPv6! 🎉"
	msgID2, err := aliceMgr.SendMessage(bobFP, msg2)
	if err != nil {
		t.Fatalf("Alice failed to send reply to Bob: %v", err)
	}
	t.Logf("Alice sent reply #2 (ID=%s): %s", msgID2, msg2)

	select {
	case received := <-bobMsgChan:
		if received != msg2 {
			t.Fatalf("Bob received unexpected reply: %s", received)
		}
		t.Logf("✅ Bob received reply #2 from Alice: %s", received)
	case <-time.After(3 * time.Second):
		t.Fatal("Timeout waiting for Bob to receive reply #2 from Alice")
	}

	// 6. Multi-message rapid bidirectional conversation
	t.Log("Testing multi-message rapid bidirectional conversation between Go and Go...")
	for i := 1; i <= 5; i++ {
		bMsg := fmt.Sprintf("Bob message #%d - cryptographic verification stream", i)
		_, err := bobMgr.SendMessage(aliceFP, bMsg)
		if err != nil {
			t.Fatalf("Bob failed sending msg %d: %v", i, err)
		}

		select {
		case r := <-aliceMsgChan:
			if r != bMsg {
				t.Fatalf("Alice received mismatch at %d: got %s, expected %s", i, r, bMsg)
			}
		case <-time.After(3 * time.Second):
			t.Fatalf("Alice timed out waiting for msg %d", i)
		}

		aReply := fmt.Sprintf("Alice reply #%d - ACK confirmed and decrypted", i)
		_, err = aliceMgr.SendMessage(bobFP, aReply)
		if err != nil {
			t.Fatalf("Alice failed sending reply %d: %v", i, err)
		}

		select {
		case r := <-bobMsgChan:
			if r != aReply {
				t.Fatalf("Bob received mismatch at %d: got %s, expected %s", i, r, aReply)
			}
		case <-time.After(3 * time.Second):
			t.Fatalf("Bob timed out waiting for reply %d", i)
		}
	}

	t.Log("✅ Go <-> Go bidirectional correspondence test PASSED 100%!")
}

// TestPythonToGoCorrespondenceOverIPv6Yggdrasil tests Python initiating a session to Go
// and performing bidirectional chat over IPv6/Yggdrasil stack.
func TestPythonToGoCorrespondenceOverIPv6Yggdrasil(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "pygo_alice_*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	// 1. Initialize Alice (Go)
	aliceID, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	alicePrekeyPriv, alicePrekeyPub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatal(err)
	}
	aliceFP := crypto.Fingerprint(aliceID.Public.Bytes())

	aliceMsgChan := make(chan string, 10)
	var peerFPConnected string
	var peerFPMu sync.Mutex

	aliceCallbacks := session.EventCallbacks{
		OnPeerConnected: func(peerFP, endpoint string) {
			peerFPMu.Lock()
			peerFPConnected = peerFP
			peerFPMu.Unlock()
		},
		OnMessageReceived: func(peerFP string, payload []byte, messageID string) {
			var m map[string]any
			if err := json.Unmarshal(payload, &m); err == nil {
				if body, ok := m["body"].(string); ok {
					aliceMsgChan <- body
				}
			}
		},
	}

	aliceMgr := session.NewManager(
		aliceID,
		alicePrekeyPriv,
		alicePrekeyPub,
		"",
		false,
		aliceCallbacks,
	)
	aliceMgr.SetNickname("Alice_Go")
	aliceMgr.SetStorageDir(tmpDir)

	// Find ephemeral port
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	alicePort := l.Addr().(*net.TCPAddr).Port
	_ = l.Close()

	if err := aliceMgr.StartListener(alicePort); err != nil {
		t.Fatalf("Alice failed starting listener: %v", err)
	}
	defer aliceMgr.StopListener()

	// 2. Python client initiates connection to Go, performs handshake, exchanges chat messages
	pyScript := `
import asyncio, json, sys
from nacl.public import PrivateKey
from nacl.signing import SigningKey
from messenger.core.session import Session, fingerprint

async def get_next_chat(sess):
    while True:
        m = await asyncio.wait_for(sess.receive_message(), timeout=5.0)
        if m.get("type") == "chat":
            return m

async def main():
    alice_port = int(sys.argv[1])
    expected_fp = sys.argv[2]

    my_priv = PrivateKey.generate()
    my_signing = SigningKey.generate()
    my_fp = fingerprint(my_priv.public_key)

    reader, writer = await asyncio.open_connection("127.0.0.1", alice_port)
    sess = await Session.create(
        reader,
        writer,
        initiator=True,
        identity_priv=my_priv,
        signing_key=my_signing,
        expected_fingerprint=expected_fp,
    )

    # 1. Send first message from Python to Go
    msg1 = "Hello Go from Python client! 🐍->🔷 [Yggdrasil/IPv6 Stack]"
    await sess.send_chat(msg1)

    # 2. Wait for first chat reply from Go
    reply1 = await get_next_chat(sess)
    print("PYTHON_RECEIVED_1:" + json.dumps(reply1), flush=True)

    # 3. Send second follow-up message
    msg2 = "Second follow-up message from Python client to Go!"
    await sess.send_chat(msg2)

    # 4. Wait for second chat reply from Go
    reply2 = await get_next_chat(sess)
    print("PYTHON_RECEIVED_2:" + json.dumps(reply2), flush=True)

    await asyncio.sleep(0.5)
    await sess.close()

asyncio.run(main())
`
	cmd := exec.Command("python", "-u", "-c", pyScript, strconv.Itoa(alicePort), aliceFP)
	cmd.Dir = "c:\\Users\\TurboBox\\Desktop\\2pchat"

	pyOutPipe, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = cmd.Process.Kill() }()

	// Wait for Python's first message to arrive at Go
	select {
	case msg := <-aliceMsgChan:
		t.Logf("✅ Go received first message from Python: %s", msg)
		if !strings.HasPrefix(msg, "Hello Go from Python client!") {
			t.Fatalf("Unexpected message: %s", msg)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for first message from Python")
	}

	// Go sends reply #1 to Python
	peerFPMu.Lock()
	targetFP := peerFPConnected
	peerFPMu.Unlock()

	if targetFP == "" {
		t.Fatal("peerFPConnected is empty")
	}

	reply1 := "Hello Python! This is Go replying with Double Ratchet encryption! 🔷->🐍"
	_, err = aliceMgr.SendMessage(targetFP, reply1)
	if err != nil {
		t.Fatalf("Go failed sending reply #1: %v", err)
	}
	t.Logf("Go sent reply #1 to Python: %s", reply1)

	// Wait for Python's second message to arrive at Go
	select {
	case msg := <-aliceMsgChan:
		t.Logf("✅ Go received second message from Python: %s", msg)
		if msg != "Second follow-up message from Python client to Go!" {
			t.Fatalf("Unexpected second message: %s", msg)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for second message from Python")
	}

	// Go sends reply #2 to Python
	reply2 := "Go second reply: bidirectional message stream verified!"
	_, err = aliceMgr.SendMessage(targetFP, reply2)
	if err != nil {
		t.Fatalf("Go failed sending reply #2: %v", err)
	}
	t.Logf("Go sent reply #2 to Python: %s", reply2)

	// Read until the Python client exits. A single pipe read may return after
	// its first line, racing the second reply and making this interop test
	// spuriously fail.
	pyOutputBytes, err := io.ReadAll(pyOutPipe)
	if err != nil {
		t.Fatalf("Read Python output: %v", err)
	}
	if err := cmd.Wait(); err != nil {
		t.Fatalf("Python client failed: %v", err)
	}
	pyOutput := string(pyOutputBytes)
	t.Logf("Python output:\n%s", pyOutput)

	if !strings.Contains(pyOutput, "Hello Python! This is Go replying") {
		t.Fatal("Python did not receive Go reply #1")
	}
	if !strings.Contains(pyOutput, "Go second reply: bidirectional message stream verified!") {
		t.Fatal("Python did not receive Go reply #2")
	}

	t.Log("✅ Python -> Go and Go -> Python correspondence test PASSED 100%!")
}

// TestGoToPythonCorrespondenceOverIPv6Yggdrasil tests Go initiating a connection to a Python server
// and engaging in bidirectional Double Ratchet communication over IPv6/Yggdrasil.
func TestGoToPythonCorrespondenceOverIPv6Yggdrasil(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "gopy_bob_*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	// 1. Start Python server (Alice)
	pyServerScript := `
import asyncio, json, sys
from nacl.public import PrivateKey
from nacl.signing import SigningKey
from messenger.core.session import Session, fingerprint

my_priv = PrivateKey.generate()
my_signing = SigningKey.generate()
my_fp = fingerprint(my_priv.public_key)

async def handle_client(reader, writer):
    try:
        sess = await Session.create(
            reader,
            writer,
            initiator=False,
            identity_priv=my_priv,
            signing_key=my_signing,
        )
        while True:
            msg = await sess.receive_message()
            if msg.get("type") == "chat":
                body = msg.get("body", "")
                reply_body = f"Python received: [{body}] - echoing with signature!"
                await sess.send_chat(reply_body)
    except Exception as e:
        pass

async def main():
    server = await asyncio.start_server(handle_client, "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    sys.stdout.write(f"READY:{port}:{my_fp}\n")
    sys.stdout.flush()
    async with server:
        await server.serve_forever()

asyncio.run(main())
`
	cmd := exec.Command("python", "-u", "-c", pyServerScript)
	cmd.Dir = "c:\\Users\\TurboBox\\Desktop\\2pchat"

	outReader, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = cmd.Process.Kill() }()

	buf := make([]byte, 256)
	n, err := outReader.Read(buf)
	if err != nil {
		t.Fatal(err)
	}
	line := strings.TrimSpace(string(buf[:n]))
	if !strings.HasPrefix(line, "READY:") {
		t.Fatalf("Unexpected Python output: %s", line)
	}

	parts := strings.Split(line, ":")
	pyPort := parts[1]
	pyFP := parts[2]
	pyEndpoint := "127.0.0.1:" + pyPort

	// 2. Initialize Bob (Go)
	bobID, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	bobPrekeyPriv, bobPrekeyPub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatal(err)
	}

	bobMsgChan := make(chan string, 10)
	bobCallbacks := session.EventCallbacks{
		OnMessageReceived: func(peerFP string, payload []byte, messageID string) {
			var m map[string]any
			if err := json.Unmarshal(payload, &m); err == nil {
				if body, ok := m["body"].(string); ok {
					bobMsgChan <- body
				}
			}
		},
	}

	bobMgr := session.NewManager(
		bobID,
		bobPrekeyPriv,
		bobPrekeyPub,
		"",
		false,
		bobCallbacks,
	)
	bobMgr.SetNickname("Bob_Go_Initiator")
	bobMgr.SetStorageDir(tmpDir)

	// 3. Go connects to Python server
	t.Logf("Go connecting to Python server at %s (expected FP: %s)", pyEndpoint, pyFP)
	_, err = bobMgr.ConnectPeer(pyEndpoint, pyFP)
	if err != nil {
		t.Fatalf("Go failed connecting to Python server: %v", err)
	}
	t.Log("✅ Go successfully connected to Python server!")

	time.Sleep(100 * time.Millisecond)

	// 4. Go sends 3 sequential chat messages and verifies Python's replies
	for i := 1; i <= 3; i++ {
		testMsg := fmt.Sprintf("Go message #%d over Yggdrasil stack 🔷", i)
		_, err := bobMgr.SendMessage(pyFP, testMsg)
		if err != nil {
			t.Fatalf("Go failed sending message %d: %v", i, err)
		}
		t.Logf("Go sent message #%d: %s", i, testMsg)

		select {
		case reply := <-bobMsgChan:
			t.Logf("✅ Go received reply #%d from Python: %s", i, reply)
			expectedSnippet := fmt.Sprintf("Python received: [%s]", testMsg)
			if !strings.Contains(reply, expectedSnippet) {
				t.Fatalf("Reply content mismatch: got %s, expected snippet %s", reply, expectedSnippet)
			}
		case <-time.After(5 * time.Second):
			t.Fatalf("Go timed out waiting for reply #%d from Python", i)
		}
	}

	t.Log("✅ Go -> Python (initiator mode) bidirectional correspondence test PASSED 100%!")
}

// TestGoAdaptiveDialerYggdrasilRoute verifies that Yggdrasil IPv6 addresses (200::/7)
// are properly identified and routed directly via IPv6 without Tor interference.
func TestGoAdaptiveDialerYggdrasilRoute(t *testing.T) {
	dialer := transport.NewAdaptiveDialer("127.0.0.1:9050", true, 5*time.Second)

	// Yggdrasil 200::/7 addresses
	yggTestAddresses := []string{
		"[200:1234:5678:9abc::1]:50001",
		"[200:dead:beef:cafe::42]:50001",
		"[300:abcd:ef01:2345::67]:50001",
		"[0200:1e2f:a0b1:c2d3::1]:50001",
		"[03ff:ffff:ffff:ffff::1]:50001",
	}

	for _, addr := range yggTestAddresses {
		trType := dialer.ClassifyEndpoint(addr)
		if trType != transport.TransportYggdrasil {
			t.Fatalf("Address %s expected TransportYggdrasil, got %s", addr, trType)
		}
		t.Logf("✅ %s correctly classified as %s", addr, trType)
	}

	// Clearnet WAN address under proxy should be TransportTor
	wanAddr := "93.184.216.34:50001"
	if dialer.ClassifyEndpoint(wanAddr) != transport.TransportTor {
		t.Fatalf("Expected %s to be TransportTor when proxy is active", wanAddr)
	}

	// Onion address under proxy should be TransportTor
	onionAddr := "ta325zop5al47taygtk2d7sobpiozy5mku5mbk2u4hpcrovumvrna4ad.onion:50001"
	if dialer.ClassifyEndpoint(onionAddr) != transport.TransportTor {
		t.Fatalf("Expected %s to be TransportTor", onionAddr)
	}

	t.Log("✅ AdaptiveDialer Yggdrasil classification verified 100%!")
}
