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
	httpClient       *http.Client
	torEnabled       bool
	timeout          time.Duration
	localYggdrasilIP string
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
		},
		torEnabled: torEnabled,
		timeout:    timeout,
	}
}

// SetLocalYggdrasilIP sets the local IPv6 address to announce to trackers.
func (c *HTTPTrackerClient) SetLocalYggdrasilIP(ip string) {
	c.localYggdrasilIP = strings.Trim(strings.TrimSpace(ip), "[]")
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

	if c.localYggdrasilIP != "" {
		q.Set("ipv6", c.localYggdrasilIP)
	}

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

	return ParseHTTPAnnounceResponse(body)
}

// ParseHTTPAnnounceResponse parses Bencoded tracker dictionary response.
func ParseHTTPAnnounceResponse(data []byte) (*AnnounceResult, error) {
	if len(data) == 0 {
		return nil, errors.New("empty HTTP tracker response")
	}

	// Checks for 'failure reason'
	if idx := bytes.Index(data, []byte("14:failure reason")); idx != -1 {
		return nil, fmt.Errorf("%w: %s", ErrTrackerResponse, string(data[idx:]))
	}

	res := &AnnounceResult{
		Interval: 60,
		Peers:    make([]PeerEndpoint, 0),
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

	// 1. Look for compact IPv4 peers string (e.g. 5:peers<len>:<data>)
	if peerIdx := bytes.Index(data, []byte("5:peers")); peerIdx != -1 {
		start := peerIdx + 7
		if colon := bytes.IndexByte(data[start:], ':'); colon != -1 {
			lenStr := string(data[start : start+colon])
			if peerLen, err := strconv.Atoi(lenStr); err == nil {
				dataStart := start + colon + 1
				if dataStart+peerLen <= len(data) {
					peersBinary := data[dataStart : dataStart+peerLen]
					res.Peers = append(res.Peers, ParseCompactIPv4Peers(peersBinary)...)
				}
			}
		}
	}

	// 2. Look for compact IPv6 peers string (BEP 7: 6:peers6<len>:<data>)
	if peer6Idx := bytes.Index(data, []byte("6:peers6")); peer6Idx != -1 {
		start := peer6Idx + 8
		if colon := bytes.IndexByte(data[start:], ':'); colon != -1 {
			lenStr := string(data[start : start+colon])
			if peer6Len, err := strconv.Atoi(lenStr); err == nil {
				dataStart := start + colon + 1
				if dataStart+peer6Len <= len(data) {
					peersBinary := data[dataStart : dataStart+peer6Len]
					res.Peers = append(res.Peers, ParseCompactIPv6Peers(peersBinary)...)
				}
			}
		}
	}

	// 3. Fallback: Parse dictionary peer list e.g. [{ip: ..., port: ...}] or [onion: ..., port: ...]
	if dictIdx := bytes.Index(data, []byte("5:peersl")); dictIdx != -1 {
		dictData := data[dictIdx+7:]
		res.Peers = append(res.Peers, parseBencodeDictPeers(dictData)...)
	}

	return res, nil
}

func parseBencodeDictPeers(data []byte) []PeerEndpoint {
	var peers []PeerEndpoint
	pos := 0
	for pos < len(data) && data[pos] != 'e' {
		if data[pos] != 'd' {
			pos++
			continue
		}
		pos++ // skip 'd'
		var ipStr string
		var port int

		for pos < len(data) && data[pos] != 'e' {
			// read key
			colon := bytes.IndexByte(data[pos:], ':')
			if colon == -1 {
				break
			}
			klen, err := strconv.Atoi(string(data[pos : pos+colon]))
			if err != nil {
				break
			}
			kStart := pos + colon + 1
			if kStart+klen > len(data) {
				break
			}
			key := string(data[kStart : kStart+klen])
			pos = kStart + klen

			// read value
			if key == "ip" || key == "host" || key == "onion" {
				if vcolon := bytes.IndexByte(data[pos:], ':'); vcolon != -1 {
					vlen, err := strconv.Atoi(string(data[pos : pos+vcolon]))
					if err == nil {
						vStart := pos + vcolon + 1
						if vStart+vlen <= len(data) {
							ipStr = string(data[vStart : vStart+vlen])
							pos = vStart + vlen
						}
					}
				}
			} else if key == "port" {
				if data[pos] == 'i' {
					pos++
					if eIdx := bytes.IndexByte(data[pos:], 'e'); eIdx != -1 {
						port, _ = strconv.Atoi(string(data[pos : pos+eIdx]))
						pos += eIdx + 1
					}
				}
			} else {
				// skip arbitrary value
				if data[pos] == 'i' {
					if eIdx := bytes.IndexByte(data[pos:], 'e'); eIdx != -1 {
						pos += eIdx + 1
					} else {
						break
					}
				} else if vcolon := bytes.IndexByte(data[pos:], ':'); vcolon != -1 {
					vlen, err := strconv.Atoi(string(data[pos : pos+vcolon]))
					if err == nil {
						pos = pos + vcolon + 1 + vlen
					} else {
						break
					}
				} else {
					pos++
				}
			}
		}

		if ipStr != "" && port > 0 {
			ip := net.ParseIP(strings.Trim(ipStr, "[]"))
			peers = append(peers, PeerEndpoint{
				IP:   ip,
				Port: port,
				Raw:  net.JoinHostPort(ipStr, strconv.Itoa(port)),
			})
		}
		if pos < len(data) && data[pos] == 'e' {
			pos++ // skip dict 'e'
		}
	}
	return peers
}
