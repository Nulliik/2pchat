package transport

import (
	"context"
	"errors"
	"net"
	"strings"
	"sync"
	"syscall"
	"time"
)

var (
	ErrHolePunchFailed = errors.New("simultaneous TCP hole punch failed to establish connection")
	ErrHolePunchTor    = errors.New("TCP hole punch is disabled in Tor mode (use onion endpoints)")
)

// HolePuncher coordinates simultaneous TCP open attempts.
type HolePuncher struct {
	localPort int
	torActive bool
}

// NewHolePuncher creates a new HolePuncher bound to the active local listening port.
func NewHolePuncher(localPort int, torActive bool) *HolePuncher {
	return &HolePuncher{
		localPort: localPort,
		torActive: torActive,
	}
}

// SetLocalPort updates the listening port used as local source for hole punching.
func (hp *HolePuncher) SetLocalPort(port int) {
	hp.localPort = port
}

// Punch attempts simultaneous TCP hole punching towards the remote peer's public and local endpoints.
func (hp *HolePuncher) Punch(ctx context.Context, remoteEndpoints []string, maxAttempts int, roundInterval time.Duration) (net.Conn, error) {
	if hp.torActive {
		return nil, ErrHolePunchTor
	}
	if len(remoteEndpoints) == 0 {
		return nil, errors.New("no remote endpoints provided for hole punch")
	}

	validEndpoints := make([]string, 0, len(remoteEndpoints))
	for _, ep := range remoteEndpoints {
		ep = strings.TrimSpace(ep)
		if ep != "" && !strings.HasSuffix(ep, ".onion") && !strings.Contains(ep, ".onion:") {
			validEndpoints = append(validEndpoints, ep)
		}
	}
	if len(validEndpoints) == 0 {
		return nil, errors.New("no direct IP endpoints available for hole punching")
	}

	type punchResult struct {
		conn net.Conn
		err  error
		ep   string
	}

	resCh := make(chan punchResult, len(validEndpoints)*maxAttempts)
	done := make(chan struct{})
	var once sync.Once

	ctxTimeout, cancel := context.WithTimeout(ctx, time.Duration(maxAttempts)*roundInterval+2*time.Second)
	defer cancel()

	// Launch parallel simultaneous connect workers
	var wg sync.WaitGroup
	for _, ep := range validEndpoints {
		wg.Add(1)
		go func(target string) {
			defer wg.Done()

			for attempt := 0; attempt < maxAttempts; attempt++ {
				select {
				case <-done:
					return
				case <-ctxTimeout.Done():
					return
				default:
				}

				conn, err := hp.dialWithReuse(ctxTimeout, target, roundInterval)
				if err == nil && conn != nil {
					won := false
					once.Do(func() {
						won = true
						close(done)
						resCh <- punchResult{conn: conn, ep: target}
					})
					if !won {
						_ = conn.Close()
					}
					return
				}

				time.Sleep(100 * time.Millisecond)
			}
		}(ep)
	}

	// Wait for successful connection or all attempts exhausted
	select {
	case res := <-resCh:
		if res.conn != nil {
			return res.conn, nil
		}
	case <-ctxTimeout.Done():
	}

	return nil, ErrHolePunchFailed
}

func (hp *HolePuncher) dialWithReuse(ctx context.Context, target string, timeout time.Duration) (net.Conn, error) {
	dialer := &net.Dialer{
		Timeout: timeout,
		Control: func(network, address string, c syscall.RawConn) error {
			var opErr error
			err := c.Control(func(fd uintptr) {
				opErr = setReuseAddr(fd)
			})
			if err != nil {
				return err
			}
			return opErr
		},
	}

	if hp.localPort > 0 {
		dialer.LocalAddr = &net.TCPAddr{
			Port: hp.localPort,
		}
	}

	return dialer.DialContext(ctx, "tcp", target)
}
