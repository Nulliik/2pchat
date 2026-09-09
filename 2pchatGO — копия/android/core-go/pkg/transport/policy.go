package transport

import (
	"errors"
	"fmt"
)

// TransportClass represents the architectural classification of an endpoint.
type TransportClass string

const (
	TransportInvalid   TransportClass = "invalid"
	TransportLAN       TransportClass = "lan"
	TransportWAN       TransportClass = "wan"
	TransportYggdrasil TransportClass = "yggdrasil"
	TransportTor       TransportClass = "tor"
	TransportDirect    TransportClass = "direct"
)

// IsDirect returns true if the transport class represents direct LAN or WAN connection.
func (c TransportClass) IsDirect() bool {
	return c == TransportLAN || c == TransportWAN || c == TransportDirect
}

var (
	ErrPolicyDenied          = errors.New("transport: denied by network policy")
	ErrBogonAddress          = errors.New("transport: bogon or reserved address")
	ErrMulticastAddress      = errors.New("transport: multicast address is not supported")
	ErrMalformedOnionAddress = errors.New("transport: malformed or unsupported onion address (only Tor v3 supported)")
	ErrLoopbackAddress       = errors.New("transport: loopback address is prohibited")
	ErrLinkLocalAddress      = errors.New("transport: link-local address is prohibited")
	ErrUnspecifiedAddress    = errors.New("transport: unspecified address (0.0.0.0 or ::) is prohibited")
	ErrInvalidPolicyFlags    = errors.New("transport: invalid or contradictory policy flags")
)

// SECURITY INVARIANT: NetworkPolicy must use positive Allow* flags so Go's zero-value
// struct is fail-closed (deny-all). Do not invert or convert to Deny* semantics.
type NetworkPolicy struct {
	AllowLAN       bool
	AllowWAN       bool
	AllowYggdrasil bool
	AllowOnion     bool
	AllowLocalDNS  bool
}

// Pre-defined network policies.
var (
	PolicySpeed = NetworkPolicy{
		AllowLAN:       true,
		AllowWAN:       true,
		AllowYggdrasil: true,
		AllowOnion:     true,
		AllowLocalDNS:  true,
	}

	PolicyTorStrict = NetworkPolicy{
		AllowLAN:       false,
		AllowWAN:       false,
		AllowYggdrasil: false,
		AllowOnion:     true,
		AllowLocalDNS:  false,
	}
)

// Allows checks whether the given TransportClass is permitted under this policy.
// Zero-value NetworkPolicy denies everything (fail-closed).
func (p NetworkPolicy) Allows(class TransportClass) bool {
	switch class {
	case TransportLAN:
		return p.AllowLAN
	case TransportWAN:
		return p.AllowWAN
	case TransportYggdrasil:
		return p.AllowYggdrasil
	case TransportTor:
		return p.AllowOnion
	default:
		return false
	}
}

// Bitmask constants for JNI / Kotlin interop
const (
	PolicyFlagAllowLAN       = 1 << 0
	PolicyFlagAllowWAN       = 1 << 1
	PolicyFlagAllowYggdrasil = 1 << 2
	PolicyFlagAllowOnion     = 1 << 3
	PolicyFlagAllowLocalDNS  = 1 << 4
)

// PolicyFromFlags converts an integer bitmask to a NetworkPolicy.
func PolicyFromFlags(flags int) NetworkPolicy {
	return NetworkPolicy{
		AllowLAN:       (flags & PolicyFlagAllowLAN) != 0,
		AllowWAN:       (flags & PolicyFlagAllowWAN) != 0,
		AllowYggdrasil: (flags & PolicyFlagAllowYggdrasil) != 0,
		AllowOnion:     (flags & PolicyFlagAllowOnion) != 0,
		AllowLocalDNS:  (flags & PolicyFlagAllowLocalDNS) != 0,
	}
}

// ToFlags converts NetworkPolicy to an integer bitmask.
func (p NetworkPolicy) ToFlags() int {
	var flags int
	if p.AllowLAN {
		flags |= PolicyFlagAllowLAN
	}
	if p.AllowWAN {
		flags |= PolicyFlagAllowWAN
	}
	if p.AllowYggdrasil {
		flags |= PolicyFlagAllowYggdrasil
	}
	if p.AllowOnion {
		flags |= PolicyFlagAllowOnion
	}
	if p.AllowLocalDNS {
		flags |= PolicyFlagAllowLocalDNS
	}
	return flags
}

// Intersect returns the intersection of two policies (stricter rule wins).
func (p NetworkPolicy) Intersect(other NetworkPolicy) NetworkPolicy {
	return NetworkPolicy{
		AllowLAN:       p.AllowLAN && other.AllowLAN,
		AllowWAN:       p.AllowWAN && other.AllowWAN,
		AllowYggdrasil: p.AllowYggdrasil && other.AllowYggdrasil,
		AllowOnion:     p.AllowOnion && other.AllowOnion,
		AllowLocalDNS:  p.AllowLocalDNS && other.AllowLocalDNS,
	}
}

// PolicyAllFlagsMask is the bitmask of all supported policy flags.
const PolicyAllFlagsMask = PolicyFlagAllowLAN | PolicyFlagAllowWAN | PolicyFlagAllowYggdrasil | PolicyFlagAllowOnion | PolicyFlagAllowLocalDNS

// IsDenyAll returns true if the policy does not allow any network transport.
func (p NetworkPolicy) IsDenyAll() bool {
	return !p.AllowLAN && !p.AllowWAN && !p.AllowYggdrasil && !p.AllowOnion
}

// ValidateFlags validates that the provided integer bitmask represents a coherent and safe policy.
// A zero value is permitted for contacts (indicating inheritance of the global policy).
func ValidateFlags(flags int) error {
	if flags == 0 {
		return nil
	}
	if (flags & ^PolicyAllFlagsMask) != 0 {
		return fmt.Errorf("%w: unknown flag bits 0x%x", ErrInvalidPolicyFlags, flags&^PolicyAllFlagsMask)
	}
	p := PolicyFromFlags(flags)
	if p.IsDenyAll() {
		return fmt.Errorf("%w: policy allows zero network transports", ErrInvalidPolicyFlags)
	}
	if p.AllowLocalDNS && !p.AllowWAN && !p.AllowLAN {
		return fmt.Errorf("%w: AllowLocalDNS requires at least one clearnet transport (LAN or WAN)", ErrInvalidPolicyFlags)
	}
	if p.AllowWAN && !p.AllowLAN {
		return fmt.Errorf("%w: AllowWAN without AllowLAN is invalid", ErrInvalidPolicyFlags)
	}
	if p.AllowYggdrasil && !p.AllowLAN && !p.AllowWAN && !p.AllowOnion {
		return fmt.Errorf("%w: standalone Yggdrasil without overlay or clearnet transport is unsupported", ErrInvalidPolicyFlags)
	}
	return nil
}
