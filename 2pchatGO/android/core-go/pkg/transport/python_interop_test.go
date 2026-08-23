package transport

import (
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"testing"
)

func TestPythonBinaryV2FileFrameInterop(t *testing.T) {
	pythonRoot := os.Getenv("P2PCHAT_PYTHON_ROOT")
	if pythonRoot == "" {
		t.Skip("set P2PCHAT_PYTHON_ROOT to the Python core checkout to run cross-core interop")
	}
	python := `
import sys
from messenger.core.protocol import encode_file_chunk, decode_file_chunk
mode = sys.argv[1]
if mode == "encode":
    print(encode_file_chunk(bytes.fromhex(sys.argv[2]), int(sys.argv[3]), bytes.fromhex(sys.argv[4])).hex())
else:
    decoded = decode_file_chunk(bytes.fromhex(sys.argv[2]))
    print(f"{decoded['file_id']}|{decoded['chunk_index']}|{decoded['payload'].hex()}|{decoded['chunk_format']}")
`
	pythonCommand := func(args ...string) *exec.Cmd {
		cmd := exec.Command("python", args...)
		cmd.Env = append(os.Environ(), "PYTHONPATH="+pythonRoot)
		return cmd
	}

	fileID := []byte("abcdefghijkl")
	payload := []byte("cross-core-file-chunk")
	output, err := pythonCommand("-c", python, "encode", hex.EncodeToString(fileID), "9", hex.EncodeToString(payload)).CombinedOutput()
	if err != nil {
		t.Fatalf("Python frame encoding failed: %v: %s", err, output)
	}
	pythonFrame, err := hex.DecodeString(strings.TrimSpace(string(output)))
	if err != nil {
		t.Fatal(err)
	}
	decoded, err := DecodeFileChunkFrame(pythonFrame)
	if err != nil || decoded.ChunkIndex != 9 || string(decoded.Payload) != string(payload) {
		t.Fatalf("Go could not decode Python frame: decoded=%#v err=%v", decoded, err)
	}

	goFrame, err := EncodeFileChunkFrame(fileID, 9, payload)
	if err != nil {
		t.Fatal(err)
	}
	output, err = pythonCommand("-c", python, "decode", hex.EncodeToString(goFrame)).CombinedOutput()
	if err != nil {
		t.Fatalf("Python frame decoding failed: %v: %s", err, output)
	}
	const want = "YWJjZGVmZ2hpamts|9|63726f73732d636f72652d66696c652d6368756e6b|binary-v2"
	if got := strings.TrimSpace(string(output)); got != want {
		t.Fatalf("Python decoded %q, want %q", got, want)
	}
}

func TestPythonAcceptsGoBinaryV2Metadata(t *testing.T) {
	pythonRoot := os.Getenv("P2PCHAT_PYTHON_ROOT")
	if pythonRoot == "" {
		t.Skip("set P2PCHAT_PYTHON_ROOT to the Python core checkout to run cross-core interop")
	}
	metadata := &FileMetadata{
		Type:            "file_meta",
		ID:              "message-42",
		MessageID:       "message-42",
		FileID:          []byte("abcdefghijkl"),
		FileKey:         make([]byte, 32),
		FileNoncePrefix: make([]byte, 16),
		FileSize:        7,
		NumChunks:       1,
		FileHash:        make([]byte, 32),
		FileName:        "interop.txt",
	}
	raw, err := metadata.EncodeMetadataJSON()
	if err != nil {
		t.Fatal(err)
	}
	python := `
import base64, sys
from messenger.core.protocol import decode_message
meta = decode_message(base64.b64decode(sys.argv[1]))
print(f"{meta['type']}|{meta['chunk_format']}|{meta['chunk_size']}|{meta['file_id']}")
`
	cmd := exec.Command("python", "-c", python, base64.StdEncoding.EncodeToString(raw))
	cmd.Env = append(os.Environ(), "PYTHONPATH="+pythonRoot)
	output, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("Python metadata decoding failed: %v: %s", err, output)
	}
	want := fmt.Sprintf("file_meta|binary-v2|%d|YWJjZGVmZ2hpamts", DefaultChunkSize)
	if got := strings.TrimSpace(string(output)); got != want {
		t.Fatalf("Python decoded %q, want %q", got, want)
	}
}
