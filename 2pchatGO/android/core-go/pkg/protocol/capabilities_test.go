package protocol

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"
)

func TestVersionMatrix(t *testing.T) {
	for _, tc := range []struct {
		name                 string
		lv, lm, rv, rm, want int
		fail                 bool
	}{
		{"same", 1, 1, 1, 1, 1, false},
		{"older", 3, 1, 1, 1, 1, false},
		{"future", 1, 1, 4, 1, 1, false},
		{"overlap", 4, 2, 3, 3, 3, false},
		{"local floor", 3, 2, 1, 1, 0, true},
		{"remote floor", 1, 1, 4, 2, 0, true},
		{"invalid range", 2, 3, 2, 1, 0, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			local, remote := LocalCapabilities(), LocalCapabilities()
			local.ProtocolVersion, local.MinSupportedVersion = tc.lv, tc.lm
			remote.ProtocolVersion, remote.MinSupportedVersion = tc.rv, tc.rm
			got, err := Negotiate(local, remote)
			if (err != nil) != tc.fail {
				t.Fatalf("result=%+v err=%v", got, err)
			}
			if !tc.fail && (got.ProtocolVersion != tc.want || got.PeerIsOutdated != (tc.rv < tc.lv)) {
				t.Fatalf("%+v", got)
			}
		})
	}
}

func TestCapabilityIntersectionAndBaseline(t *testing.T) {
	local, remote := AndroidCapabilities(), LocalCapabilities()
	local.Capabilities = append(local.Capabilities, "future_feature_v9")
	remote.Capabilities = append(remote.Capabilities, GroupSuiteV1, "future_feature_v9")
	got, err := Negotiate(local, remote)
	if err != nil || !got.Supports(GroupSuiteV1) || got.Supports(GroupSuiteV2) || got.Supports("future_feature_v9") {
		t.Fatalf("%+v %v", got, err)
	}
	remote.Capabilities = []string{X3DH}
	if _, err := Negotiate(local, remote); err == nil {
		t.Fatal("accepted missing ratchet")
	}
}

func TestDeclarationParsing(t *testing.T) {
	valid, _ := LocalCapabilities().CanonicalBytes()
	for _, raw := range []string{
		`null`, `{}`, `[]`, string(valid) + `{}`,
		strings.Replace(string(valid), `"protocol_version":1`, `"protocol_version":1,"protocol_version":2`, 1),
		strings.Replace(string(valid), `"protocol_version":1`, `"protocol_version":1.5`, 1),
		strings.Replace(string(valid), `"protocol_version":1`, `"protocol_version":true`, 1),
		strings.Replace(string(valid), `"protocol_version":1`, `"protocol_version":65536`, 1),
		`{"protocol_version":1,"min_supported_version":1,"capabilities":null}`,
		`{"protocol_version":1,"min_supported_version":1,"capabilities":["x_v1","x_v1"]}`,
		`{"protocol_version":1,"min_supported_version":1,"capabilities":["Upper_v1"]}`,
		strings.Repeat(" ", MaxDeclarationBytes+1),
	} {
		if _, err := Parse([]byte(raw)); err == nil {
			t.Fatalf("accepted %s", raw)
		}
	}
	d := LocalCapabilities()
	d.Capabilities = append(d.Capabilities, "future_feature_v123")
	raw, _ := d.CanonicalBytes()
	raw = append(raw[:len(raw)-1], []byte(`,"future_field":{"mode":1}}`)...)
	if _, err := Parse(raw); err != nil {
		t.Fatal(err)
	}
	a, _ := d.CanonicalBytes()
	d.Capabilities[0], d.Capabilities[1] = d.Capabilities[1], d.Capabilities[0]
	b, _ := d.CanonicalBytes()
	if !reflect.DeepEqual(a, b) {
		t.Fatal("noncanonical order")
	}
}

func TestFeatureGates(t *testing.T) {
	base, _ := Negotiate(LocalCapabilities(), LocalCapabilities())
	full, _ := Negotiate(AndroidCapabilities(), AndroidCapabilities())
	v1 := full
	v1.ActiveCapabilities = []string{X3DH, DoubleRatchet, GroupSuiteV1}
	for _, tc := range []struct {
		raw     string
		state   *NegotiatedSession
		allowed bool
	}{
		{`{"type":"chat","body":"hello"}`, nil, true},
		{`{"type":"group_future_v9"}`, &full, false},
		{`{"type":"group_event_v1"}`, nil, false},
		{`{"type":"group_event_v1"}`, &base, false},
		{`{"type":"group_event_v1"}`, &v1, true},
		{`{"type":"group_event_v1","crypto_suite":"2pchat-epoch-aes256gcm-ed25519-v2"}`, &v1, false},
		{`{"type":"group_key_package_v1","suite":"2pchat-epoch-aes256gcm-ed25519-v2"}`, &v1, false},
		{`{"type":"group_event_v1","is_tombstoned":true}`, &v1, false},
		{`{"type":"group_succession_claim_v1"}`, &v1, false},
		{`{"type":"group_sync_batch_v1","events":[{"type":"group_event_v1","is_tombstoned":true}]}`, &v1, false},
		{`{"type":"group_sync_batch_v1","events":[{"type":"group_event_v1","is_tombstoned":true}]}`, &full, true},
	} {
		if err := CheckMessage([]byte(tc.raw), tc.state); (err == nil) != tc.allowed {
			t.Fatalf("%s: %v", tc.raw, err)
		}
	}
}

func FuzzDeclaration(f *testing.F) {
	raw, _ := json.Marshal(LocalCapabilities())
	f.Add(raw)
	f.Add([]byte(`null`))
	f.Fuzz(func(t *testing.T, raw []byte) {
		if d, err := Parse(raw); err == nil {
			canonical, err := d.CanonicalBytes()
			if err != nil {
				t.Fatal(err)
			}
			if _, err := Parse(canonical); err != nil {
				t.Fatal(err)
			}
		}
	})
}
