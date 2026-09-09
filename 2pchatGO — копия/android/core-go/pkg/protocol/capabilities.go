// Package protocol defines application compatibility independently of handshake
// and cipher packet versions. Capability names describe immutable wire behavior.
package protocol

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"regexp"
	"sort"
)

const CurrentVersion = 1
const MinSupportedVersion = 1
const MaxDeclarationBytes = 8192
const MaxCapabilities = 64

const X3DH = "pairwise_x3dh_v1"
const DoubleRatchet = "pairwise_double_ratchet_v1"
const GroupSuiteV1 = "group_suite_v1"
const GroupSuiteV2 = "group_suite_v2"
const GroupTombstones = "group_tombstones_v1"
const GroupSuccession = "group_succession_v1"
const GroupSuccessionQuery = "group_succession_query_v1"
const GroupSuccessionV3 = "group_succession_v3"
const GroupInviteRelay = "group_invite_relay_v1"

var known = map[string]int{
	X3DH: 1, DoubleRatchet: 1, GroupSuiteV1: 1, GroupSuiteV2: 1, GroupTombstones: 1, GroupSuccession: 1,
	GroupSuccessionQuery: 1, GroupSuccessionV3: 1, GroupInviteRelay: 1,
}
var capabilityName = regexp.MustCompile(`^[a-z][a-z0-9_]*_v[1-9][0-9]*$`)
var ErrIncompatible = errors.New("incompatible application protocol")

type Declaration struct {
	ProtocolVersion     int      `json:"protocol_version"`
	MinSupportedVersion int      `json:"min_supported_version"`
	Capabilities        []string `json:"capabilities"`
}

// LocalCapabilities only advertises features implemented by the standalone core.
func LocalCapabilities() Declaration {
	return Declaration{CurrentVersion, MinSupportedVersion, []string{X3DH, DoubleRatchet}}
}

// AndroidCapabilities includes the Kotlin group runtime shipped with this core.
// Discovery and local retention policies are deliberately not session features.
func AndroidCapabilities() Declaration {
	d := LocalCapabilities()
	d.Capabilities = append(d.Capabilities, GroupSuiteV1, GroupSuiteV2, GroupTombstones, GroupSuccession, GroupSuccessionQuery, GroupInviteRelay)
	return d
}

func (d Declaration) Clone() Declaration {
	if d.Capabilities != nil {
		d.Capabilities = append([]string{}, d.Capabilities...)
	}
	return d
}

func (d Declaration) Validate() error {
	if d.MinSupportedVersion < 1 || d.ProtocolVersion < d.MinSupportedVersion || d.ProtocolVersion > 65535 || d.Capabilities == nil || len(d.Capabilities) > MaxCapabilities {
		return errors.New("invalid capability declaration bounds")
	}
	seen := make(map[string]bool)
	for _, c := range d.Capabilities {
		if len(c) > 64 || !capabilityName.MatchString(c) || seen[c] {
			return errors.New("invalid or duplicate capability")
		}
		seen[c] = true
	}
	return nil
}

// Parse rejects ambiguous duplicate fields, fractions and missing required fields.
// Unknown fields and well-formed unknown capabilities remain forward compatible.
func Parse(raw []byte) (Declaration, error) {
	var d Declaration
	if len(raw) > MaxDeclarationBytes {
		return d, errors.New("capability declaration too large")
	}
	dec := json.NewDecoder(bytes.NewReader(raw))
	t, err := dec.Token()
	if err != nil || t != json.Delim('{') {
		return d, errors.New("expected capability object")
	}
	seen := map[string]bool{}
	for dec.More() {
		key, err := dec.Token()
		if err != nil {
			return d, err
		}
		name, ok := key.(string)
		if !ok || seen[name] {
			return d, errors.New("duplicate capability field")
		}
		seen[name] = true
		var value json.RawMessage
		if err := dec.Decode(&value); err != nil {
			return d, err
		}
		switch name {
		case "protocol_version":
			err = json.Unmarshal(value, &d.ProtocolVersion)
		case "min_supported_version":
			err = json.Unmarshal(value, &d.MinSupportedVersion)
		case "capabilities":
			err = json.Unmarshal(value, &d.Capabilities)
		}
		if err != nil {
			return d, err
		}
	}
	if _, err := dec.Token(); err != nil {
		return d, err
	}
	if _, err := dec.Token(); err != io.EOF {
		return d, errors.New("trailing declaration data")
	}
	return d, d.Validate()
}

func (d Declaration) CanonicalBytes() ([]byte, error) {
	if err := d.Validate(); err != nil {
		return nil, err
	}
	d = d.Clone()
	sort.Strings(d.Capabilities)
	return json.Marshal(d)
}

type NegotiatedSession struct {
	ProtocolVersion    int      `json:"protocol_version"`
	ActiveCapabilities []string `json:"active_capabilities"`
	PeerIsOutdated     bool     `json:"peer_is_outdated"`
	PeerIsLegacy       bool     `json:"peer_is_legacy"`
}

func (s NegotiatedSession) Supports(c string) bool {
	for _, active := range s.ActiveCapabilities {
		if active == c {
			return true
		}
	}
	return false
}

func Negotiate(local, remote Declaration) (NegotiatedSession, error) {
	var result NegotiatedSession
	if err := local.Validate(); err != nil {
		return result, err
	}
	if err := remote.Validate(); err != nil {
		return result, err
	}
	version := min(local.ProtocolVersion, remote.ProtocolVersion)
	if version < max(local.MinSupportedVersion, remote.MinSupportedVersion) {
		return result, ErrIncompatible
	}
	result.ProtocolVersion = version
	result.PeerIsOutdated = remote.ProtocolVersion < local.ProtocolVersion
	result.ActiveCapabilities = []string{}
	r := make(map[string]bool)
	for _, c := range remote.Capabilities {
		r[c] = true
	}
	for _, c := range local.Capabilities {
		if since, ok := known[c]; ok && since <= version && r[c] {
			result.ActiveCapabilities = append(result.ActiveCapabilities, c)
		}
	}
	sort.Strings(result.ActiveCapabilities)
	for _, required := range []string{X3DH, DoubleRatchet} {
		if !result.Supports(required) {
			return NegotiatedSession{}, fmt.Errorf("%w: missing %s", ErrIncompatible, required)
		}
	}
	return result, nil
}

// Legacy means absence of a declaration, not an invented release version.
func Legacy() NegotiatedSession {
	return NegotiatedSession{ActiveCapabilities: []string{X3DH, DoubleRatchet}, PeerIsLegacy: true}
}
