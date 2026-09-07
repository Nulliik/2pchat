package protocol

import (
	"encoding/json"
	"fmt"
	"strings"
)

var groupFrames = map[string]bool{
	"group_event_v1": true, "group_invite_v1": true, "group_invite_response_v1": true,
	"group_key_package_v1": true, "group_store_ack_v1": true, "group_sync_request_v1": true,
	"group_sync_batch_v1": true, "group_roster_snapshot_v1": true,
	"group_attachment_request_v1": true, "group_attachment_block_v1": true,
	"group_join_request_v1": true, "group_typing_v1": true, "group_key_request_v1": true,
	"group_succession_cert_v1": true, "group_owner_heartbeat_v1": true,
	"group_succession_revocation_v1": true, "group_succession_claim_v1": true,
}

// CheckMessage guards wire extensions at the final send boundary, including
// queued messages after reconnect. Never rewrite signed events or cipher suites.
func CheckMessage(raw []byte, session *NegotiatedSession) error {
	var msg map[string]any
	if err := json.Unmarshal(raw, &msg); err != nil {
		return err
	}
	kind, _ := msg["type"].(string)
	if !strings.HasPrefix(kind, "group_") {
		return nil
	}
	if !groupFrames[kind] {
		return fmt.Errorf("unsupported group frame %s", kind)
	}
	if session == nil {
		return fmt.Errorf("group features await capability negotiation")
	}
	return checkGroup(msg, *session, 0)
}

func checkGroup(msg map[string]any, session NegotiatedSession, depth int) error {
	if depth > 1 {
		return fmt.Errorf("nested group sync batch")
	}
	if !session.Supports(GroupSuiteV1) {
		return fmt.Errorf("peer does not support %s", GroupSuiteV1)
	}
	kind, _ := msg["type"].(string)
	if strings.HasPrefix(kind, "group_succession_") || kind == "group_owner_heartbeat_v1" {
		if !session.Supports(GroupSuccession) {
			return fmt.Errorf("peer does not support %s", GroupSuccession)
		}
	}
	for _, field := range []string{"crypto_suite", "suite"} {
		if suite, _ := msg[field].(string); suite != "" {
			switch suite {
			case "2pchat-epoch-aes256gcm-ed25519-v1":
			case "2pchat-epoch-aes256gcm-ed25519-v2":
				if !session.Supports(GroupSuiteV2) {
					return fmt.Errorf("peer does not support %s", GroupSuiteV2)
				}
			default:
				return fmt.Errorf("unsupported group crypto suite")
			}
		}
	}
	if tombstone, _ := msg["is_tombstoned"].(bool); tombstone && !session.Supports(GroupTombstones) {
		return fmt.Errorf("peer does not support %s", GroupTombstones)
	}
	if kind == "group_sync_batch_v1" {
		events, ok := msg["events"].([]any)
		if !ok || len(events) > 100 {
			return fmt.Errorf("invalid sync events")
		}
		for _, value := range events {
			event, ok := value.(map[string]any)
			if !ok || event["type"] != "group_event_v1" {
				return fmt.Errorf("invalid sync event")
			}
			if err := checkGroup(event, session, depth+1); err != nil {
				return err
			}
		}
	}
	return nil
}
