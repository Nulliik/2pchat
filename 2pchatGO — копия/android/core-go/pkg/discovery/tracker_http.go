package discovery

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
	"twopchat/core/pkg/transport"
)

// HTTPTrackerClient implements HTTP/HTTPS BitTorrent tracker announces.
type HTTPTrackerClient struct {
	httpClient *http.Client
	torEnabled bool
	timeout    time.Duration
}

// NewHTTPTrackerClient creates a new HTTP tracker client.
func NewHTTPTrackerClient(dialer *transport.AdaptiveDialer, torEnabled bool, timeout time.Duration) *HTTPTrackerClient {
	if timeout <= 0 {
		timeout = DefaultTrackerTimeout
	}

	transportObj := &http.Transport{
		MaxIdleConns:       10,
		IdleConnTimeout:    30 * time.Second,
		DisableCompression: true,
	}

	if dialer != nil {
		transportObj.DialContext = dialer.DialContext
	}

	return &HTTPTrackerClient{
		httpClient: &http.Client{
			Transport: transportObj,
			Timeout:   timeout,
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				if len(via) >= 3 {
					return errors.New("stopped after 3 redirects")
				}
				if len(via) > 0 {
					origScheme := via[0].URL.Scheme
					if origScheme == "https" && req.URL.Scheme == "http" {
						return fmt.Errorf("insecure HTTP redirect from HTTPS prohibited (%s -> %s)", via[0].URL.String(), req.URL.String())
					}
				}
				return nil
			},
		},
		torEnabled: torEnabled,
		timeout:    timeout,
	}
}

// SetTorEnabled updates whether Tor proxy is enabled.
func (c *HTTPTrackerClient) SetTorEnabled(enabled bool) {
	c.torEnabled = enabled
}

// urlEncodeBinary creates raw percent-encoded byte string for info_hash/peer_id without escaping UTF-8.
func urlEncodeBinary(b []byte) string {
	var buf bytes.Buffer
	for _, c := range b {
		if (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_' || c == '~' {
			buf.WriteByte(c)
		} else {
			fmt.Fprintf(&buf, "%%%02X", c)
		}
	}
	return buf.String()
}

// Announce sends an HTTP GET request to announce to an HTTP/HTTPS tracker.
func (c *HTTPTrackerClient) Announce(
	ctx context.Context,
	trackerURL string,
	infoHash [20]byte,
	peerID [20]byte,
	listenPort int,
) (*AnnounceResult, error) {
	u, err := url.Parse(trackerURL)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") {
		return nil, fmt.Errorf("invalid HTTP tracker URL: %s", trackerURL)
	}

	q := u.Query()
	q.Set("port", strconv.Itoa(listenPort))
	q.Set("uploaded", "0")
	q.Set("downloaded", "0")
	q.Set("left", "0")
	q.Set("compact", "1")
	q.Set("numwant", "50")
	q.Set("event", "started")

	rawQuery := q.Encode()
	if rawQuery != "" {
		rawQuery += "&"
	}
	rawQuery += fmt.Sprintf("info_hash=%s&peer_id=%s", urlEncodeBinary(infoHash[:]), urlEncodeBinary(peerID[:]))
	u.RawQuery = rawQuery

	req, err := http.NewRequestWithContext(ctx, "GET", u.String(), nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "2PChat/1.0")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("HTTP tracker request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("tracker returned HTTP status: %d", resp.StatusCode)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read tracker response body: %w", err)
	}

	return parseHTTPAnnounceResponse(body, isYggdrasilTrackerHost(u.Hostname()))
}

// ParseHTTPAnnounceResponse parses Bencoded tracker dictionary response.
func ParseHTTPAnnounceResponse(data []byte) (*AnnounceResult, error) {
	return parseHTTPAnnounceResponse(data, false)
}

func parseHTTPAnnounceResponse(data []byte, yggdrasilTracker bool) (*AnnounceResult, error) {
	if len(data) == 0 {
		return nil, errors.New("empty HTTP tracker response")
	}

	// Simple bencode dictionary scanner
	// Checks for 'failure reason'
	if idx := bytes.Index(data, []byte("14:failure reason")); idx != -1 {
		return nil, fmt.Errorf("%w: %s", ErrTrackerResponse, string(data[idx:]))
	}

	res := &AnnounceResult{
		Interval: 60,
	}

	// Look for interval (e.g. 8:intervali1800e)
	if intIdx := bytes.Index(data, []byte("8:intervali")); intIdx != -1 {
		start := intIdx + 11
		if end := bytes.IndexByte(data[start:], 'e'); end != -1 {
			if interval, err := strconv.Atoi(string(data[start : start+end])); err == nil {
				res.Interval = interval
			}
		}
	}

	// Some Yggdrasil-only trackers return 18-byte IPv6 compact entries under
	// the legacy `peers` key instead of BEP 7's `peers6` key. Decoding that
	// payload in six-byte chunks creates three bogus IPv4 candidates per peer.
	if peersBinary, ok := compactPeerValue(data, "peers"); ok {
		if yggdrasilTracker {
			res.Peers = append(res.Peers, ParseCompactIPv6Peers(peersBinary)...)
		} else {
			res.Peers = append(res.Peers, ParseCompactIPv4Peers(peersBinary)...)
		}
	}
	if peers6Binary, ok := compactPeerValue(data, "peers6"); ok {
		res.Peers = append(res.Peers, ParseCompactIPv6Peers(peers6Binary)...)
	}

	return res, nil
}

func compactPeerValue(data []byte, key string) ([]byte, bool) {
	marker := []byte(strconv.Itoa(len(key)) + ":" + key)
	idx := bytes.Index(data, marker)
	if idx < 0 {
		return nil, false
	}
	lengthStart := idx + len(marker)
	colon := bytes.IndexByte(data[lengthStart:], ':')
	if colon < 1 {
		return nil, false
	}
	peerLen, err := strconv.Atoi(string(data[lengthStart : lengthStart+colon]))
	if err != nil || peerLen < 0 {
		return nil, false
	}
	valueStart := lengthStart + colon + 1
	if valueStart+peerLen > len(data) {
		return nil, false
	}
	return data[valueStart : valueStart+peerLen], true
}

func isYggdrasilTrackerHost(host string) bool {
	ip := net.ParseIP(host)
	if ip == nil || !strings.Contains(host, ":") {
		return false
	}
	bytes16 := ip.To16()
	return len(bytes16) == net.IPv6len && bytes16[0]&0xfe == 0x02
}
