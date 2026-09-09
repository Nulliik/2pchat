package session

import (
	"context"
	"errors"
	"net"
	"twopchat/core/pkg/diagnostics"
	"twopchat/core/pkg/transport"
)

func (m *Manager) SetDiagnosticsEnabled(enabled bool)        { m.diagnostics.SetEnabled(enabled) }
func (m *Manager) DiagnosticsSnapshot() diagnostics.Snapshot { return m.diagnostics.Snapshot() }

// This classification performs no DNS or network IO and retains no endpoints.
func (m *Manager) diagnosticCandidateKind(candidates []string) diagnostics.Kind {
	result := diagnostics.Unknown
	for i, endpoint := range candidates {
		class, err := m.dialer.ClassifyEndpoint(endpoint)
		kind := diagnostics.Unknown
		if err == nil {
			switch {
			case class == transport.TransportTor:
				kind = diagnostics.Tor
			case class == transport.TransportYggdrasil:
				kind = diagnostics.Yggdrasil
			case class.IsDirect():
				kind = diagnostics.Direct
			}
		}
		if i > 0 && kind != result {
			return diagnostics.Mixed
		}
		result = kind
	}
	return result
}

// Do not classify by parsing error text: it can contain peer-controlled data.
func diagnosticTimeout(err error) bool {
	var networkError net.Error
	return errors.Is(err, context.DeadlineExceeded) ||
		(errors.As(err, &networkError) && networkError.Timeout())
}
