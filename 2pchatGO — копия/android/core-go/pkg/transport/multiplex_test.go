package transport

import (
	"bytes"
	"net"
	"sync"
	"testing"
	"time"
)

func TestMultiplexedSessionStreams(t *testing.T) {
	clientConn, serverConn := net.Pipe()

	clientMux, err := NewMultiplexedSession(clientConn, false)
	if err != nil {
		t.Fatalf("NewMultiplexedSession client failed: %v", err)
	}
	defer func() { _ = clientMux.Close() }()

	serverMux, err := NewMultiplexedSession(serverConn, true)
	if err != nil {
		t.Fatalf("NewMultiplexedSession server failed: %v", err)
	}
	defer func() { _ = serverMux.Close() }()

	var wg sync.WaitGroup
	wg.Add(3)

	// Stream 1: Chat Message
	go func() {
		defer wg.Done()
		chatPayload := []byte("Hello from Yamux StreamChat!")
		if err := clientMux.WriteToStream(StreamTypeChat, chatPayload); err != nil {
			t.Errorf("WriteToStream Chat failed: %v", err)
			return
		}
	}()

	// Stream 2: File Transfer (Simulate 64KB chunk)
	go func() {
		defer wg.Done()
		fileChunk := bytes.Repeat([]byte{0x42}, 65536)
		if err := clientMux.WriteToStream(StreamTypeFile, fileChunk); err != nil {
			t.Errorf("WriteToStream File failed: %v", err)
			return
		}
	}()

	// Stream 0: Control Ping
	go func() {
		defer wg.Done()
		controlPing := []byte("PING-12345")
		if err := clientMux.WriteToStream(StreamTypeControl, controlPing); err != nil {
			t.Errorf("WriteToStream Control failed: %v", err)
			return
		}
	}()

	// Receiver side on server
	receivedChat := make(chan []byte, 1)
	receivedFile := make(chan []byte, 1)
	receivedControl := make(chan []byte, 1)

	go func() {
		for i := 0; i < 3; i++ {
			select {
			case stream := <-serverMux.streamCh:
				go func(s net.Conn) {
					data, err := ReadFromStream(s, 1024*1024)
					if err != nil {
						return
					}
					if len(data) == len("Hello from Yamux StreamChat!") {
						receivedChat <- data
					} else if len(data) == 65536 {
						receivedFile <- data
					} else if len(data) == len("PING-12345") {
						receivedControl <- data
					}
				}(stream)
			case <-time.After(3 * time.Second):
				return
			}
		}
	}()

	wg.Wait()

	select {
	case chat := <-receivedChat:
		if string(chat) != "Hello from Yamux StreamChat!" {
			t.Errorf("Chat mismatch: %s", chat)
		}
	case <-time.After(3 * time.Second):
		t.Errorf("Timeout waiting for StreamChat")
	}

	select {
	case file := <-receivedFile:
		if len(file) != 65536 || file[0] != 0x42 {
			t.Errorf("File chunk mismatch")
		}
	case <-time.After(3 * time.Second):
		t.Errorf("Timeout waiting for StreamFile")
	}

	select {
	case ctrl := <-receivedControl:
		if string(ctrl) != "PING-12345" {
			t.Errorf("Control mismatch: %s", ctrl)
		}
	case <-time.After(3 * time.Second):
		t.Errorf("Timeout waiting for StreamControl")
	}
}
