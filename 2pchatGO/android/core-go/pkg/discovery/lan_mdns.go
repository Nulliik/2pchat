package discovery

import (
	"fmt"
	"net"
	"sync/atomic"
)

// RefreshAnnouncement re-binds the multicast/broadcast UDP socket on network interface changes and sends immediate beacons.
func (e *LANEngine) RefreshAnnouncement() error {
	e.mu.Lock()
	if atomic.LoadInt32(&e.running) == 0 {
		e.mu.Unlock()
		return nil
	}

	if e.listener != nil {
		_ = e.listener.Close()
		e.listener = nil
	}

	lAddr := &net.UDPAddr{Port: e.udpPort}
	conn, err := net.ListenUDP("udp4", lAddr)
	if err != nil {
		e.mu.Unlock()
		// If re-binding listener fails temporarily on interface switch, still broadcast beacon
		go e.sendBeacon()
		return fmt.Errorf("failed to re-bind LAN UDP listener on port %d: %w", e.udpPort, err)
	}

	e.listener = conn
	e.wg.Add(1)
	go e.listenLoop(conn)
	e.mu.Unlock()

	// Send immediate beacons on the refreshed network interface
	go e.sendBeacon()
	return nil
}
