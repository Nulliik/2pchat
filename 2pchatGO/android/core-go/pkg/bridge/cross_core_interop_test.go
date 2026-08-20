package bridge

import (
	"context"
	"encoding/json"
	"net"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"testing"
	"time"
	"twopchat/core/pkg/discovery"
	"twopchat/core/pkg/session"
)

func TestPythonGoRendezvousKeyCrossCompatibility(t *testing.T) {
	// Test vector: Null#36571c05
	goHex := discovery.DeriveRendezvousKeyHex("Null", "36571c05")

	pyScript := `
import sys
from messenger.core.discovery_rendezvous import derive_rendezvous_key
key = derive_rendezvous_key("Null", "36571c05")
print(key.hex())
`
	cmd := exec.Command("python", "-c", pyScript)
	cmd.Dir = "c:\\Users\\TurboBox\\Desktop\\2pchat"
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("Python execution failed: %v: %s", err, string(out))
	}

	pyHex := strings.TrimSpace(string(out))
	if goHex != pyHex {
		t.Fatalf("Rendezvous info_hash mismatch! Go=%s, Python=%s", goHex, pyHex)
	}
	t.Logf("✅ Python-Go Rendezvous Key match: %s", goHex)
}

func TestPythonGoLiveHandshakeAndIdentityExchange(t *testing.T) {
	// 1. Start Go Manager as "Alice"
	mgr := GetManager()
	tmpDir, err := os.MkdirTemp("", "go_py_interop_*")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tmpDir)

	mgr.SetStorageDir(tmpDir)
	mgr.SetNickname("Alice")
	if err := mgr.Init(); err != nil {
		t.Fatal(err)
	}

	alicePort := 0
	// Find ephemeral port for Alice
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	alicePort = l.Addr().(*net.TCPAddr).Port
	_ = l.Close()

	var msgReceived = make(chan string, 1)
	mgr.SetCallbacks(session.EventCallbacks{
		OnMessageReceived: func(peerFP string, payload []byte, messageID string) {
			var m map[string]any
			if err := json.Unmarshal(payload, &m); err == nil {
				if body, ok := m["body"].(string); ok {
					msgReceived <- body
				}
			}
		},
	}, nil)

	if err := mgr.StartListener(alicePort); err != nil {
		t.Fatal(err)
	}
	defer mgr.StopListener()

	aliceFP := mgr.GetFingerprint()

	// 2. Python dials Go node "Alice", performs X3DH handshake, sends probe, verifies identity, and sends chat
	pyClientScript := `
import asyncio, json, sys
from nacl.public import PrivateKey
from nacl.signing import SigningKey
from messenger.core.session import Session

async def main():
    alice_port = int(sys.argv[1])
    expected_fp = sys.argv[2]
    
    my_priv = PrivateKey.generate()
    my_signing = SigningKey.generate()
    reader, writer = await asyncio.open_connection("127.0.0.1", alice_port)
    
    sess = await Session.create(
        reader,
        writer,
        initiator=True,
        identity_priv=my_priv,
        signing_key=my_signing,
        expected_fingerprint=expected_fp,
    )
    
    # Send identity_probe to Alice
    await sess.send_reliable({"type": "identity_probe"})
    
    # Receive identity_info response
    while True:
        msg = await asyncio.wait_for(sess.receive_message(), timeout=5.0)
        if msg.get("type") == "identity_info":
            break
            
    # Send chat message to Alice
    await sess.send_chat("Hello Alice from Python client!")
    await asyncio.sleep(0.5)
    await sess.close()
    print("SUCCESS")

asyncio.run(main())
`
	cmd := exec.Command("python", "-c", pyClientScript, strconv.Itoa(alicePort), aliceFP)
	cmd.Dir = "c:\\Users\\TurboBox\\Desktop\\2pchat"
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("Python client failed: %v: %s", err, string(out))
	}

	select {
	case body := <-msgReceived:
		if body != "Hello Alice from Python client!" {
			t.Fatalf("Unexpected message body received: %s", body)
		}
		t.Logf("✅ Go successfully received message from Python: %s", body)
	case <-time.After(5 * time.Second):
		t.Fatal("Timeout waiting for message from Python")
	}
}

func TestGoProbesPythonNode(t *testing.T) {
	// 1. Start Python node as "Bob" listening on local port
	pyServerScript := `
import asyncio, json, sys
from nacl.public import PrivateKey
from nacl.signing import SigningKey
from messenger.core.session import Session
from messenger.core.identity import fingerprint

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
            if msg.get("type") == "identity_probe":
                await sess.send_reliable({
                    "type": "identity_info",
                    "nickname": "Bob",
                    "fingerprint": my_fp,
                    "listen_port": 50001
                })
    except Exception as e:
        pass

async def main():
    server = await asyncio.start_server(
        handle_client,
        "127.0.0.1", 0
    )
    port = server.sockets[0].getsockname()[1]
    # Print port and fingerprint
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
	defer func() {
		_ = cmd.Process.Kill()
	}()

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
	bobPort := parts[1]
	bobFP := parts[2]
	bobEndpoint := "127.0.0.1:" + bobPort

	// 2. Go probes Python node Bob via VerifyLiveEndpoint
	mgr := GetManager()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	res, err := mgr.VerifyLiveEndpoint(ctx, bobEndpoint, "Bob", bobFP)
	if err != nil {
		t.Fatalf("Go failed to verify Python node Bob: %v", err)
	}

	if res["nickname"] != "Bob" || res["fingerprint"] != bobFP || res["verified"] != true {
		t.Fatalf("Verification metadata mismatch: %+v", res)
	}

	t.Logf("✅ Go successfully probed and verified Python node Bob: %+v", res)
}
