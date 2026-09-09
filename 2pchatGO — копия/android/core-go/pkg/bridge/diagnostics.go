package bridge

import (
	"encoding/json"
	"twopchat/core/pkg/diagnostics"
)

// SetDiagnosticsEnabled never initializes networking or identity. Repeating it
// clears the collection window, including observations still in flight.
func (m *SessionManager) SetDiagnosticsEnabled(enabled bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.diagnosticsEnabled = enabled
	if m.netManager != nil {
		m.netManager.SetDiagnosticsEnabled(enabled)
	}
}

func (m *SessionManager) GetPublicDiagnosticsJSON() string {
	m.mu.RLock()
	defer m.mu.RUnlock()
	var empty diagnostics.Collector
	snapshot := empty.Snapshot()
	snapshot.Enabled = m.diagnosticsEnabled
	if m.netManager != nil {
		snapshot = m.netManager.DiagnosticsSnapshot()
	}
	// Snapshot has a closed schema: fixed strings, booleans, and integer schema
	// version only. Marshal cannot fail for this type.
	encoded, _ := json.Marshal(snapshot)
	return string(encoded)
}
