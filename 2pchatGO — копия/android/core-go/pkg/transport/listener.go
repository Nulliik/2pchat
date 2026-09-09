package transport

import (
	"context"
	"errors"
	"fmt"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

var (
	ErrListenerAlreadyRunning = errors.New("TCP listener is already running")
	ErrListenerNotRunning     = errors.New("TCP listener is not running")
)

// ConnectionHandler is called when a new incoming connection is accepted.
type ConnectionHandler func(conn net.Conn)

// AsyncListener manages a non-blocking TCP listener with dual-stack support.
type AsyncListener struct {
	mu         sync.Mutex
	listener   net.Listener
	running    int32
	port       int
	ctx        context.Context
	cancelFunc context.CancelFunc
	wg         sync.WaitGroup
}

// NewAsyncListener creates a new AsyncListener.
func NewAsyncListener() *AsyncListener {
	return &AsyncListener{}
}

// Start binds to the specified port using PolicySpeed and begins accepting connections.
func (l *AsyncListener) Start(port int, handler ConnectionHandler) error {
	return l.StartWithPolicy(port, PolicySpeed, handler)
}

// StartWithPolicy binds to the specified port adhering to the NetworkPolicy:
// When policy denies WAN and LAN (such as PolicyTorStrict), it binds strictly to "127.0.0.1:port",
// ensuring clearnet packets cannot reach the socket from outside the host.
func (l *AsyncListener) StartWithPolicy(port int, policy NetworkPolicy, handler ConnectionHandler) error {
	l.mu.Lock()
	defer l.mu.Unlock()

	if atomic.LoadInt32(&l.running) == 1 {
		if port == 0 || port == l.port {
			return nil
		}
		return ErrListenerAlreadyRunning
	}

	var addr string
	if !policy.AllowWAN && !policy.AllowLAN {
		addr = fmt.Sprintf("127.0.0.1:%d", port)
	} else {
		addr = fmt.Sprintf(":%d", port)
	}

	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("failed to bind TCP listener on %s: %w", addr, err)
	}

	actualPort := listener.Addr().(*net.TCPAddr).Port
	l.listener = listener
	l.port = actualPort
	l.ctx, l.cancelFunc = context.WithCancel(context.Background())
	atomic.StoreInt32(&l.running, 1)

	l.wg.Add(1)
	go l.acceptLoop(listener, handler)

	return nil
}

// RebindWithPolicy restarts the listener on the same port with the updated policy.
func (l *AsyncListener) RebindWithPolicy(policy NetworkPolicy, handler ConnectionHandler) error {
	l.mu.Lock()
	port := l.port
	if atomic.LoadInt32(&l.running) == 0 {
		l.mu.Unlock()
		return nil
	}

	atomic.StoreInt32(&l.running, 0)
	if l.cancelFunc != nil {
		l.cancelFunc()
	}
	if l.listener != nil {
		_ = l.listener.Close()
	}
	l.mu.Unlock()
	l.wg.Wait()

	return l.StartWithPolicy(port, policy, handler)
}

// Addr returns the listener's network address, or nil if not running.
func (l *AsyncListener) Addr() net.Addr {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.listener != nil {
		return l.listener.Addr()
	}
	return nil
}

// IsRunning returns true if the listener is active.
func (l *AsyncListener) IsRunning() bool {
	return atomic.LoadInt32(&l.running) == 1
}

func (l *AsyncListener) acceptLoop(listener net.Listener, handler ConnectionHandler) {
	defer l.wg.Done()

	for {
		conn, err := listener.Accept()
		if err != nil {
			if atomic.LoadInt32(&l.running) == 0 {
				return // Normal shutdown
			}
			// Transient accept error (network switch, socket abort, or temporary OS error)
			time.Sleep(50 * time.Millisecond)
			continue
		}

		// Handle each incoming connection asynchronously in its own goroutine
		go func(c net.Conn) {
			handler(c)
		}(conn)
	}
}

// Stop closes the listener and waits for the accept loop to terminate.
func (l *AsyncListener) Stop() error {
	l.mu.Lock()
	defer l.mu.Unlock()

	if atomic.LoadInt32(&l.running) == 0 {
		return nil
	}

	atomic.StoreInt32(&l.running, 0)
	if l.cancelFunc != nil {
		l.cancelFunc()
	}

	var err error
	if l.listener != nil {
		err = l.listener.Close()
		l.listener = nil
	}

	l.wg.Wait()
	return err
}

// Port returns the bound listening port.
func (l *AsyncListener) Port() int {
	return l.port
}
