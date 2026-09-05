package transport

import (
	"errors"
	"testing"
)

func TestClassifyEndpoint_ComprehensiveTable(t *testing.T) {
	v3Onion := "expyuzz5wqqfdgah56trgah56trgah56trgah56trgah56trgah56trg.onion:50001"
	v2Onion := "3g2upl4pq6kufc4m.onion:50001"
	badBase32Onion := "0189uzz5wqqfdgah56trgah56trgah56trgah56trgah56trgah56trg.onion:50001" // 0,1,8,9 not base32

	tests := []struct {
		name          string
		endpoint      string
		expectedClass TransportClass
		expectedErr   error
	}{
		// Tor v3 Onion
		{
			name:          "Valid v3 Onion",
			endpoint:      v3Onion,
			expectedClass: TransportTor,
			expectedErr:   nil,
		},
		{
			name:          "Reject v2 Onion (16 chars)",
			endpoint:      v2Onion,
			expectedClass: TransportInvalid,
			expectedErr:   ErrMalformedOnionAddress,
		},
		{
			name:          "Reject invalid base32 in Onion",
			endpoint:      badBase32Onion,
			expectedClass: TransportInvalid,
			expectedErr:   ErrMalformedOnionAddress,
		},

		// CGNAT (100.64.0.0/10) -> WAN per П2
		{
			name:          "CGNAT 100.64.0.1 -> WAN",
			endpoint:      "100.64.0.1:50001",
			expectedClass: TransportWAN,
			expectedErr:   nil,
		},
		{
			name:          "CGNAT upper bound 100.127.255.254 -> WAN",
			endpoint:      "100.127.255.254:50001",
			expectedClass: TransportWAN,
			expectedErr:   nil,
		},

		// Yggdrasil (200::/7) vs ULA (fc00::/7) order of checks per П2
		{
			name:          "Yggdrasil 200::1 -> Yggdrasil",
			endpoint:      "[200:1234:5678::1]:50001",
			expectedClass: TransportYggdrasil,
			expectedErr:   nil,
		},
		{
			name:          "Yggdrasil 300::1 (within 200::/7) -> Yggdrasil",
			endpoint:      "[300:abcd:ef01::1]:50001",
			expectedClass: TransportYggdrasil,
			expectedErr:   nil,
		},
		{
			name:          "ULA IPv6 fc00::1 -> LAN (checked after Yggdrasil)",
			endpoint:      "[fc00::1]:50001",
			expectedClass: TransportLAN,
			expectedErr:   nil,
		},
		{
			name:          "ULA IPv6 fd12:3456:789a::1 -> LAN",
			endpoint:      "[fd12:3456:789a::1]:50001",
			expectedClass: TransportLAN,
			expectedErr:   nil,
		},

		// IPv4 Private LAN (RFC 1918)
		{
			name:          "RFC1918 192.168.1.100 -> LAN",
			endpoint:      "192.168.1.100:50001",
			expectedClass: TransportLAN,
			expectedErr:   nil,
		},
		{
			name:          "RFC1918 10.0.0.5 -> LAN",
			endpoint:      "10.0.0.5:50001",
			expectedClass: TransportLAN,
			expectedErr:   nil,
		},
		{
			name:          "RFC1918 172.16.5.10 -> LAN",
			endpoint:      "172.16.5.10:50001",
			expectedClass: TransportLAN,
			expectedErr:   nil,
		},

		// IPv4-mapped IPv6 (::ffff:) must unmap and classify underlying IPv4
		{
			name:          "IPv4-mapped LAN ::ffff:192.168.1.1 -> LAN",
			endpoint:      "[::ffff:192.168.1.1]:50001",
			expectedClass: TransportLAN,
			expectedErr:   nil,
		},
		{
			name:          "IPv4-mapped CGNAT ::ffff:100.64.1.1 -> WAN",
			endpoint:      "[::ffff:100.64.1.1]:50001",
			expectedClass: TransportWAN,
			expectedErr:   nil,
		},
		{
			name:          "IPv4-mapped Loopback ::ffff:127.0.0.1 -> LAN",
			endpoint:      "[::ffff:127.0.0.1]:50001",
			expectedClass: TransportLAN,
			expectedErr:   nil,
		},

		// Bogons / Documentation ranges per П2
		{
			name:          "Bogon 198.18.0.1 -> Invalid",
			endpoint:      "198.18.0.1:50001",
			expectedClass: TransportInvalid,
			expectedErr:   ErrBogonAddress,
		},
		{
			name:          "Bogon 192.0.2.1 (TEST-NET-1) -> Invalid",
			endpoint:      "192.0.2.1:50001",
			expectedClass: TransportInvalid,
			expectedErr:   ErrBogonAddress,
		},
		{
			name:          "Bogon 240.0.0.1 (Class E) -> Invalid",
			endpoint:      "240.0.0.1:50001",
			expectedClass: TransportInvalid,
			expectedErr:   ErrBogonAddress,
		},
		{
			name:          "IPv6 Doc 2001:db8::1 -> Invalid",
			endpoint:      "[2001:db8::1]:50001",
			expectedClass: TransportInvalid,
			expectedErr:   ErrBogonAddress,
		},

		// Multicast ranges per П2
		{
			name:          "IPv4 Multicast 224.0.0.1 -> Invalid",
			endpoint:      "224.0.0.1:50001",
			expectedClass: TransportInvalid,
			expectedErr:   ErrMulticastAddress,
		},
		{
			name:          "IPv6 Multicast ff02::1 -> Invalid",
			endpoint:      "[ff02::1]:50001",
			expectedClass: TransportInvalid,
			expectedErr:   ErrMulticastAddress,
		},

		// Loopback (LAN) & Unspecified & Link-Local
		{
			name:          "IPv4 Loopback 127.0.0.1 -> LAN",
			endpoint:      "127.0.0.1:50001",
			expectedClass: TransportLAN,
			expectedErr:   nil,
		},
		{
			name:          "IPv6 Loopback ::1 -> LAN",
			endpoint:      "[::1]:50001",
			expectedClass: TransportLAN,
			expectedErr:   nil,
		},
		{
			name:          "IPv4 Unspecified 0.0.0.0 -> Invalid",
			endpoint:      "0.0.0.0:50001",
			expectedClass: TransportInvalid,
			expectedErr:   ErrUnspecifiedAddress,
		},
		{
			name:          "IPv6 Unspecified :: -> Invalid",
			endpoint:      "[::]:50001",
			expectedClass: TransportInvalid,
			expectedErr:   ErrUnspecifiedAddress,
		},
		{
			name:          "IPv4 Link-Local 169.254.1.1 -> Invalid",
			endpoint:      "169.254.1.1:50001",
			expectedClass: TransportInvalid,
			expectedErr:   ErrLinkLocalAddress,
		},
		{
			name:          "IPv6 Link-Local fe80::1 -> Invalid",
			endpoint:      "[fe80::1]:50001",
			expectedClass: TransportInvalid,
			expectedErr:   ErrLinkLocalAddress,
		},

		// Global Unicast WAN
		{
			name:          "Public IPv4 8.8.8.8 -> WAN",
			endpoint:      "8.8.8.8:50001",
			expectedClass: TransportWAN,
			expectedErr:   nil,
		},
		{
			name:          "Global IPv6 2a00:1450:4001:828::200e -> WAN",
			endpoint:      "[2a00:1450:4001:828::200e]:50001",
			expectedClass: TransportWAN,
			expectedErr:   nil,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			class, err := ClassifyEndpoint(tc.endpoint)
			if class != tc.expectedClass {
				t.Errorf("ClassifyEndpoint(%q) class = %v, expected %v", tc.endpoint, class, tc.expectedClass)
			}
			if tc.expectedErr != nil {
				if err == nil || !errors.Is(err, tc.expectedErr) {
					t.Errorf("ClassifyEndpoint(%q) err = %v, expected %v", tc.endpoint, err, tc.expectedErr)
				}
			} else {
				if err != nil {
					t.Errorf("ClassifyEndpoint(%q) unexpected error: %v", tc.endpoint, err)
				}
			}
		})
	}
}
