package session

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"
	"twopchat/core/pkg/crypto"
	"twopchat/core/pkg/transport"
)

// ActorCommand represents an asynchronous operation sent to a PeerActor.
type ActorCommand interface {
	isActorCommand()
}

// CmdSendChat requests sending an encrypted text message.
type CmdSendChat struct {
	Body     string
	Nickname string
	RespChan chan Result
}

func (c CmdSendChat) isActorCommand() {}

// CmdSendBinary requests sending an arbitrary binary payload.
type CmdSendBinary struct {
	Payload  []byte
	RespChan chan Result
}

func (c CmdSendBinary) isActorCommand() {}

// CmdClose requests graceful shutdown of the peer actor.
type CmdClose struct {
	Reason   string
	RespChan chan error
}

func (c CmdClose) isActorCommand() {}

// Result represents the outcome of an actor command.
type Result struct {
	MessageID string
	Err       error
}

// PeerActor is an isolated, autonomous Actor managing state, queues, and streams for a single peer.
type PeerActor struct {
	peerFP        string
	endpoint      string
	isInitiator   bool
	isTor         bool
	inbox         chan ActorCommand
	drState       *crypto.SessionState
	muxSession    *transport.MultiplexedSession
	streamMu      sync.RWMutex
	chatStream    net.Conn
	controlStream net.Conn
	fileStream    net.Conn
	callbacks     EventCallbacks
	pendingAcks   map[string]chan bool
	receivedIDsMu sync.Mutex
	receivedIDs   map[string]bool
	receivedOrder []string
	counter       uint64
	online        int32
	ctx           context.Context
	cancel        context.CancelFunc
	closeOnce     sync.Once
}

// NewPeerActor spawns a new autonomous Actor for a connected peer.
func NewPeerActor(
	peerFP, endpoint string,
	isInitiator, isTor bool,
	drState *crypto.SessionState,
	muxSession *transport.MultiplexedSession,
	callbacks EventCallbacks,
) (*PeerActor, error) {
	ctx, cancel := context.WithCancel(context.Background())

	actor := &PeerActor{
		peerFP:        peerFP,
		endpoint:      endpoint,
		isInitiator:   isInitiator,
		isTor:         isTor,
		inbox:         make(chan ActorCommand, 256),
		drState:       drState,
		muxSession:    muxSession,
		callbacks:     callbacks,
		pendingAcks:   make(map[string]chan bool),
		receivedIDs:   make(map[string]bool),
		receivedOrder: make([]string, 0, 128),
		online:        1,
		ctx:           ctx,
		cancel:        cancel,
	}

	// Open or retrieve default Yamux logical streams
	if muxSession != nil {
		if isInitiator {
			chatStream, err := muxSession.OpenStream(transport.StreamTypeChat)
			if err == nil {
				actor.setChatStream(chatStream)
				go actor.readStream(chatStream)
			}
			ctrlStream, err := muxSession.OpenStream(transport.StreamTypeControl)
			if err == nil {
				actor.controlStream = ctrlStream
			}
		}
		go actor.acceptStreamsLoop()
	}

	go actor.eventLoop()

	if callbacks.OnPeerConnected != nil {
		callbacks.OnPeerConnected(peerFP, endpoint)
	}

	return actor, nil
}

func (a *PeerActor) getChatStream() net.Conn {
	a.streamMu.RLock()
	defer a.streamMu.RUnlock()
	return a.chatStream
}

func (a *PeerActor) setChatStream(s net.Conn) {
	a.streamMu.Lock()
	defer a.streamMu.Unlock()
	a.chatStream = s
}

// acceptStreamsLoop accepts logical streams dispatched by Yamux.
func (a *PeerActor) acceptStreamsLoop() {
	if a.muxSession == nil {
		return
	}
	for {
		select {
		case <-a.ctx.Done():
			return
		case s, ok := <-a.muxSession.StreamChannel():
			if !ok || s == nil {
				return
			}
			if a.getChatStream() == nil {
				a.setChatStream(s)
			}
			go a.readStream(s)
		}
	}
}

// eventLoop processes messages sequentially for this peer without locks.
func (a *PeerActor) eventLoop() {
	defer func() {
		atomic.StoreInt32(&a.online, 0)
		if a.callbacks.OnPeerDisconnected != nil {
			a.callbacks.OnPeerDisconnected(a.peerFP, "actor terminated")
		}
		if a.muxSession != nil {
			_ = a.muxSession.Close()
		}
		if a.drState != nil {
			a.drState.Zeroize()
		}
	}()

	for {
		select {
		case <-a.ctx.Done():
			return

		case cmd := <-a.inbox:
			switch c := cmd.(type) {
			case CmdSendChat:
				msgID, err := a.handleSendChat(c.Body, c.Nickname)
				c.RespChan <- Result{MessageID: msgID, Err: err}

			case CmdSendBinary:
				msgID, err := a.handleSendBinary(c.Payload)
				c.RespChan <- Result{MessageID: msgID, Err: err}

			case CmdClose:
				a.cancel()
				c.RespChan <- nil
				return
			}
		}
	}
}

// handleSendChat serializes, encrypts, and transmits a chat message.
func (a *PeerActor) handleSendChat(body, nickname string) (string, error) {
	c := atomic.AddUint64(&a.counter, 1)
	msgID := fmt.Sprintf("%d-%d", time.Now().UnixNano(), c)

	msg := NewChatMessage(msgID, body, nickname)
	raw, err := EncodeMessage(msg)
	if err != nil {
		return "", err
	}

	return a.handleSendBinary(raw)
}

// handleSendBinary encrypts with Double Ratchet and writes to the Yamux Chat Stream.
func (a *PeerActor) handleSendBinary(payload []byte) (string, error) {
	c := atomic.AddUint64(&a.counter, 1)
	msgID := fmt.Sprintf("%d-%d", time.Now().UnixNano(), c)

	// Format frame: [0x02 Magic] [2 bytes ID len] [ID bytes] [Payload bytes]
	idBytes := []byte(msgID)
	frame := make([]byte, 1+2+len(idBytes)+len(payload))
	frame[0] = 0x02
	binary.BigEndian.PutUint16(frame[1:3], uint16(len(idBytes)))
	copy(frame[3:3+len(idBytes)], idBytes)
	copy(frame[3+len(idBytes):], payload)

	ciphertext, err := a.drState.EncryptMessage(frame)
	if err != nil {
		return "", err
	}

	chat := a.getChatStream()
	if chat == nil && a.muxSession != nil {
		if cs, err := a.muxSession.OpenStream(transport.StreamTypeChat); err == nil {
			a.setChatStream(cs)
			chat = cs
			go a.readStream(cs)
		}
	}

	if chat != nil {
		// Write 4-byte length prefix + ciphertext
		var lenBuf [4]byte
		binary.BigEndian.PutUint32(lenBuf[:], uint32(len(ciphertext)))
		if _, err := chat.Write(lenBuf[:]); err != nil {
			return "", err
		}
		if _, err := chat.Write(ciphertext); err != nil {
			return "", err
		}
	}

	return msgID, nil
}

// readStream reads incoming frames from a stream.
func (a *PeerActor) readStream(stream net.Conn) {
	if stream == nil {
		return
	}
	defer func() {
		_ = stream.Close()
		// Only terminate the entire actor if the primary chatStream disconnected
		if stream == a.getChatStream() {
			_ = a.Close("chat stream disconnected")
		}
	}()

	for {
		data, err := transport.ReadFromStream(stream, transport.MaxFrameSize)
		if err != nil {
			return
		}

		plaintext, err := a.drState.DecryptMessage(data)
		if err != nil {
			continue
		}

		if len(plaintext) > 0 && plaintext[0] == 0x02 && len(plaintext) >= 3 {
			idLen := int(binary.BigEndian.Uint16(plaintext[1:3]))
			if len(plaintext) >= 3+idLen {
				msgID := string(plaintext[3 : 3+idLen])
				payload := plaintext[3+idLen:]

				// Deduplicate under lock
				a.receivedIDsMu.Lock()
				if a.receivedIDs[msgID] {
					a.receivedIDsMu.Unlock()
					continue
				}
				a.receivedIDs[msgID] = true
				a.receivedOrder = append(a.receivedOrder, msgID)
				if len(a.receivedOrder) > MaxReceivedIDsHistory {
					oldest := a.receivedOrder[0]
					a.receivedOrder = a.receivedOrder[1:]
					delete(a.receivedIDs, oldest)
				}
				a.receivedIDsMu.Unlock()

				if a.callbacks.OnMessageReceived != nil {
					a.callbacks.OnMessageReceived(a.peerFP, payload, msgID)
				}
				continue
			}
		}

		// Fallback for legacy JSON
		msgMap, err := DecodeMessage(plaintext)
		if err == nil && a.callbacks.OnMessageReceived != nil {
			msgID, _ := msgMap["id"].(string)
			raw, _ := EncodeMessage(msgMap)
			a.callbacks.OnMessageReceived(a.peerFP, raw, msgID)
		}
	}
}

// SendChat issues an async command to the actor.
func (a *PeerActor) SendChat(body, nickname string) (string, error) {
	resp := make(chan Result, 1)
	select {
	case a.inbox <- CmdSendChat{Body: body, Nickname: nickname, RespChan: resp}:
		res := <-resp
		return res.MessageID, res.Err
	case <-a.ctx.Done():
		return "", errors.New("peer actor is closed")
	}
}

// SendBinary issues an async binary payload command to the actor.
func (a *PeerActor) SendBinary(payload []byte) (string, error) {
	resp := make(chan Result, 1)
	select {
	case a.inbox <- CmdSendBinary{Payload: payload, RespChan: resp}:
		res := <-resp
		return res.MessageID, res.Err
	case <-a.ctx.Done():
		return "", errors.New("peer actor is closed")
	}
}

// IsOnline returns whether the actor is currently running.
func (a *PeerActor) IsOnline() bool {
	return atomic.LoadInt32(&a.online) == 1
}

// Close gracefully terminates the peer actor.
func (a *PeerActor) Close(reason string) error {
	a.closeOnce.Do(func() {
		a.cancel()
	})
	return nil
}
