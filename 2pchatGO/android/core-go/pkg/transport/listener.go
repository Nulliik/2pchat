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

// Start binds to the specified port and begins accepting connections in background goroutines.
func (l *AsyncListener) Start(port int, handler ConnectionHandler) error {
	l.mu.Lock()
	defer l.mu.Unlock()

	if atomic.LoadInt32(&l.running) == 1 {
		return ErrListenerAlreadyRunning
	}

	addr := fmt.Sprintf(":%d", port)
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

// IsRunning returns true if the listener is currently active.
func (l *AsyncListener) IsRunning() bool {
	return atomic.LoadInt32(&l.running) == 1
}
