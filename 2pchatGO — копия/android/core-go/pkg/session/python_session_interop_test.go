package session

import (
	"encoding/base64"
	"fmt"
	"net"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"
	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/transport"
)

func TestPythonGoSessionChatAndFileEnvelopeInterop(t *testing.T) {
	testPythonGoSessionInterop(t, false)
}

func TestPythonGoNegotiatedSessionInterop(t *testing.T) {
	testPythonGoSessionInterop(t, true)
}

func testPythonGoSessionInterop(t *testing.T, negotiate bool) {
	pythonRoot := os.Getenv("P2PCHAT_PYTHON_ROOT")
	if pythonRoot == "" {
		t.Skip("set P2PCHAT_PYTHON_ROOT to the Python core checkout to run cross-core interop")
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()

	identity, err := crypto.GenerateIdentityKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	prekeyPriv, prekeyPub, err := crypto.GenerateX25519Keypair()
	if err != nil {
		t.Fatal(err)
	}
	serverErr := make(chan error, 1)
	go func() {
		conn, acceptErr := listener.Accept()
		if acceptErr != nil {
			serverErr <- acceptErr
			return
		}
		sess, sessionErr := NewSession(conn, false, identity, prekeyPriv, prekeyPub, "", 5*time.Second)
		if sessionErr != nil {
			serverErr <- sessionErr
			return
		}
		defer sess.Close()
		if negotiate {
			if _, err := sess.SendReliable(map[string]any{"type": "identity_info"}); err != nil {
				serverErr <- err
				return
			}
			hello := <-sess.Messages()
			state := sess.NegotiatedProtocol()
			if hello["type"] != "identity_info" || state == nil || state.ProtocolVersion != 1 || state.PeerIsLegacy {
				serverErr <- fmt.Errorf("unexpected Python capability state: %+v", state)
				return
			}
		}

		chat := <-sess.Messages()
		if chat["type"] != "chat" || chat["body"] != "hello from Python" {
			serverErr <- fmt.Errorf("unexpected Python chat: %#v", chat)
			return
		}
		if _, sendErr := sess.SendReliable(map[string]any{
			"type": "chat", "body": "hello from Go", "timestamp": time.Now().Unix(),
		}); sendErr != nil {
			serverErr <- sendErr
			return
		}

		meta := <-sess.Messages()
		if meta["type"] != "file_meta" || meta["chunk_format"] != transport.FileChunkFormatV2 {
			serverErr <- fmt.Errorf("unexpected Python metadata: %#v", meta)
			return
		}
		chunk := <-sess.Messages()
		if chunk["type"] != "file_chunk" || chunk["chunk_format"] != transport.FileChunkFormatV2 || string(chunk["payload"].([]byte)) != "python-chunk" {
			serverErr <- fmt.Errorf("unexpected Python file chunk: %#v", chunk)
			return
		}

		goFileID := []byte("go-file-id12")
		if _, sendErr := sess.SendReliable(map[string]any{
			"type": "file_meta", "file_id": base64.StdEncoding.EncodeToString(goFileID),
			"file_name": "go.txt", "file_size": 8, "num_chunks": 1,
			"chunk_size": transport.DefaultChunkSize, "chunk_format": transport.FileChunkFormatV2,
			"ack_window": transport.DefaultFileChunkAckWindow,
		}); sendErr != nil {
			serverErr <- sendErr
			return
		}
		if _, sendErr := sess.SendReliableFileChunk(goFileID, 0, []byte("go-chunk")); sendErr != nil {
			serverErr <- sendErr
			return
		}
		serverErr <- nil
	}()

	python := `
import asyncio, base64, sys
from messenger.core.session import Session
from messenger.core.protocol import FILE_CHUNK_FORMAT, DEFAULT_FILE_CHUNK_SIZE

async def main():
    host, port = sys.argv[1], int(sys.argv[2])
    reader, writer = await asyncio.open_connection(host, port)
    session = await Session.create(reader, writer, initiator=True)
    if sys.argv[3] == "true":
        hello = await session.receive_message()
        assert hello["type"] == "identity_info"
        assert session.negotiated_protocol.protocol_version == 1
        assert not session.negotiated_protocol.peer_is_legacy
        await session.send_reliable({"type": "identity_info"})
    await session.send_chat("hello from Python")
    reply = await session.receive_message()
    assert reply["type"] == "chat" and reply["body"] == "hello from Go", reply

    file_id = b"py-file-id12"
    await session.send_reliable({
        "type": "file_meta", "file_id": base64.b64encode(file_id).decode(),
        "file_name": "python.txt", "file_size": 12, "num_chunks": 1,
        "chunk_size": DEFAULT_FILE_CHUNK_SIZE, "chunk_format": FILE_CHUNK_FORMAT,
        "ack_window": 4,
    })
    await session.send_file_chunk(file_id, 0, b"python-chunk")

    go_meta = await session.receive_message()
    go_chunk = await session.receive_message()
    assert go_meta["type"] == "file_meta" and go_meta["chunk_format"] == FILE_CHUNK_FORMAT, go_meta
    assert go_chunk["type"] == "file_chunk" and go_chunk["payload"] == b"go-chunk", go_chunk
    print("chat=ok file=ok")
    await session.close()

asyncio.run(main())
`
	host, port, err := net.SplitHostPort(listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command("python", "-c", python, host, port, fmt.Sprint(negotiate))
	cmd.Env = append(os.Environ(), "PYTHONPATH="+pythonRoot)
	output, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("Python session interop failed: %v: %s", err, output)
	}
	if got := strings.TrimSpace(string(output)); got != "chat=ok file=ok" {
		t.Fatalf("unexpected Python result: %q", got)
	}
	if err := <-serverErr; err != nil {
		t.Fatal(err)
	}
}
