package bridge

import (
	"encoding/json"
	"testing"
	"twopchat/core/pkg/diagnostics"
)

func TestDiagnosticsConsentDoesNotInitializeIdentityOrNetworking(t *testing.T) {
	var m SessionManager
	m.SetDiagnosticsEnabled(true)
	var snapshot diagnostics.Snapshot
	if err := json.Unmarshal([]byte(m.GetPublicDiagnosticsJSON()), &snapshot); err != nil {
		t.Fatal(err)
	}
	if !snapshot.Enabled || snapshot.SchemaVersion != 1 {
		t.Fatal("missing consent/schema")
	}
	if m.identity != nil || m.netManager != nil || m.discoverySvc != nil {
		t.Fatal("diagnostics initialized unrelated services")
	}
	m.SetDiagnosticsEnabled(false)
	if err := json.Unmarshal([]byte(m.GetPublicDiagnosticsJSON()), &snapshot); err != nil {
		t.Fatal(err)
	}
	if snapshot.Enabled {
		t.Fatal("opt out not reflected")
	}
}
