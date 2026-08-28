package discovery

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
)

const (
	DefaultLANPort        = 50002
	DefaultBeaconInterval = 5 * time.Second
	LANServiceName        = "2pchat"
)

// LANBeacon represents the payload broadcast over local subnet.
type LANBeacon struct {
	Service     string `json:"service"`
	Fingerprint string `json:"fingerprint"`
	Port        int    `json:"port"`
	Timestamp   int64  `json:"timestamp"`
}

// LANDiscoveryHandler is called when a local peer is detected via LAN broadcast.
type LANDiscoveryHandler func(peerFP string, endpoint string)

// LANEngine handles local network peer discovery via UDP broadcast/multicast.
type LANEngine struct {
	mu          sync.Mutex
	fingerprint string
	tcpPort     int
	udpPort     int
	running     int32
	listener    *net.UDPConn
	handler     LANDiscoveryHandler
	ctx         context.Context
	cancel      context.CancelFunc
	wg          sync.WaitGroup
}

// NewLANEngine creates a new LAN discovery engine.
func NewLANEngine(fingerprint string, tcpPort, udpPort int, handler LANDiscoveryHandler) *LANEngine {
	if udpPort <= 0 {
		udpPort = DefaultLANPort
	}
	return &LANEngine{
		fingerprint: fingerprint,
		tcpPort:     tcpPort,
		udpPort:     udpPort,
		handler:     handler,
	}
}

// Start launches the background LAN beacon listener and periodic broadcaster.
func (e *LANEngine) Start() error {
	e.mu.Lock()
	defer e.mu.Unlock()

	if atomic.LoadInt32(&e.running) == 1 {
		return nil
	}

	lAddr := &net.UDPAddr{Port: e.udpPort}
	conn, err := net.ListenUDP("udp4", lAddr)
	if err != nil {
		return fmt.Errorf("failed to bind LAN UDP listener on port %d: %w", e.udpPort, err)
	}

	e.listener = conn
	e.ctx, e.cancel = context.WithCancel(context.Background())
	atomic.StoreInt32(&e.running, 1)

	e.wg.Add(2)
	go e.listenLoop(conn)
	go e.broadcastLoop()

	return nil
}

func (e *LANEngine) listenLoop(conn *net.UDPConn) {
	defer e.wg.Done()
	buf := make([]byte, 1024)

	for {
		n, rAddr, err := conn.ReadFromUDP(buf)
		if err != nil {
			return
		}

		var beacon LANBeacon
		if err := json.Unmarshal(buf[:n], &beacon); err != nil {
			continue
		}

		if beacon.Service != LANServiceName || beacon.Fingerprint == e.fingerprint || beacon.Port <= 0 {
			continue // Ignore our own beacons and foreign packets
		}

		endpoint := net.JoinHostPort(rAddr.IP.String(), strconv.Itoa(beacon.Port))
		if e.handler != nil {
			e.handler(beacon.Fingerprint, endpoint)
		}
	}
}

func (e *LANEngine) broadcastLoop() {
	defer e.wg.Done()
	ticker := time.NewTicker(DefaultBeaconInterval)
	defer ticker.Stop()

	// Initial broadcast on start
	e.sendBeacon()

	for {
		select {
		case <-e.ctx.Done():
			return
		case <-ticker.C:
			e.sendBeacon()
		}
	}
}

func (e *LANEngine) sendBeacon() {
	beacon := LANBeacon{
		Service:     LANServiceName,
		Fingerprint: e.fingerprint,
		Port:        e.tcpPort,
		Timestamp:   time.Now().Unix(),
	}

	data, err := json.Marshal(beacon)
	if err != nil {
		return
	}

	// 1. Send to 255.255.255.255:udpPort
	bAddr := &net.UDPAddr{
		IP:   net.IPv4bcast,
		Port: e.udpPort,
	}

	if conn, err := net.DialUDP("udp4", nil, bAddr); err == nil {
		_, _ = conn.Write(data)
		_ = conn.Close()
	}

	// 2. Send to directed broadcast on all active non-loopback network interfaces
	ifaces, err := net.Interfaces()
	if err != nil {
		return
	}
	for _, iface := range ifaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			ipnet, ok := addr.(*net.IPNet)
			if !ok || ipnet.IP.To4() == nil {
				continue
			}
			ip := ipnet.IP.To4()
			mask := ipnet.Mask
			if len(mask) == 4 {
				bcast := net.IPv4(
					ip[0]|^mask[0],
					ip[1]|^mask[1],
					ip[2]|^mask[2],
					ip[3]|^mask[3],
				)
				dest := &net.UDPAddr{IP: bcast, Port: e.udpPort}
				if conn, err := net.DialUDP("udp4", nil, dest); err == nil {
					_, _ = conn.Write(data)
					_ = conn.Close()
				}
			}
		}
	}
}

// Stop halts the LAN broadcaster and closes the UDP socket.
func (e *LANEngine) Stop() error {
	e.mu.Lock()
	if atomic.LoadInt32(&e.running) == 0 {
		e.mu.Unlock()
		return nil
	}

	atomic.StoreInt32(&e.running, 0)
	if e.cancel != nil {
		e.cancel()
	}
	if e.listener != nil {
		_ = e.listener.Close()
		e.listener = nil
	}
	e.mu.Unlock()

	e.wg.Wait()
	return nil
}

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
