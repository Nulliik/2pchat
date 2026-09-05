#!/usr/bin/env python3
"""
Independent Cryptographic Test Vector Generator for 2pchat Group Chat.
Generates deterministic KAT test vectors for roster_hash and event_id using hashlib.sha256.
Outputs identical JSON files to:
- android/core-go/testdata/group_crypto_test_vectors.json
- android/app/src/test/resources/group_crypto_test_vectors.json
"""

import hashlib
import json
import os
import sys

def compute_roster_hash(entries):
    # Lexicographically sort "deviceId:signingKey" entries, join with \n, SHA-256
    sorted_entries = sorted(entries)
    roster_string = "\n".join(sorted_entries)
    return hashlib.sha256(roster_string.encode("utf-8")).hexdigest()

def compute_event_id(fields):
    # Canonical event envelope format for SHA-256 event ID
    lines = [
        "2pchat-group-event-signature-v1",
        "1",
        str(fields["group_id"]),
        str(fields["epoch"]),
        str(fields["kind"]),
        str(fields["author_fingerprint"]),
        str(fields["author_device_id"]),
        str(fields["author_signing_key"]),
        str(fields["author_sequence"]),
        str(fields.get("previous_author_event") or ""),
        str(fields.get("control_head") or ""),
        str(fields["hlc_physical_ms"]),
        str(fields["hlc_logical"]),
        str(fields.get("target_event_id") or ""),
        str(fields["nonce_base64"]),
        str(fields["ciphertext_base64"]),
        str(fields["crypto_suite"]),
        str(fields["expires_at_ms"]),
    ]
    canonical = "\n".join(lines)
    event_id = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    return canonical, event_id

def main():
    roster_vectors = [
        {
            "name": "basic_three_members_out_of_order",
            "entries": [
                "carol-dev-003:carol-key-abcde",
                "alice-dev-001:alice-key-12345",
                "bob-dev-002:bob-key-67890",
            ],
            "expected_roster_hash": compute_roster_hash([
                "carol-dev-003:carol-key-abcde",
                "alice-dev-001:alice-key-12345",
                "bob-dev-002:bob-key-67890",
            ]),
        },
        {
            "name": "single_owner_epoch0",
            "entries": [
                "dev-owner-001:key-owner-base64",
            ],
            "expected_roster_hash": compute_roster_hash([
                "dev-owner-001:key-owner-base64",
            ]),
        },
        {
            "name": "multiple_members_with_similar_prefixes",
            "entries": [
                "dev-member-1:key-1",
                "dev-member-10:key-10",
                "dev-member-2:key-2",
            ],
            "expected_roster_hash": compute_roster_hash([
                "dev-member-1:key-1",
                "dev-member-10:key-10",
                "dev-member-2:key-2",
            ]),
        },
    ]

    event_field_cases = [
        {
            "name": "sample_message_v1_suite",
            "group_id": "grp-vector-1",
            "epoch": 1,
            "kind": "message",
            "author_fingerprint": "fp-alice",
            "author_device_id": "dev-alice",
            "author_signing_key": "key-alice",
            "author_sequence": 1,
            "previous_author_event": "",
            "control_head": "",
            "hlc_physical_ms": 100000,
            "hlc_logical": 0,
            "target_event_id": "",
            "nonce_base64": "bm9uY2U=",
            "ciphertext_base64": "Y2lwaGVydGV4dA==",
            "crypto_suite": "2pchat-epoch-aes256gcm-ed25519-v1",
            "expires_at_ms": 0,
        },
        {
            "name": "sample_message_v2_suite",
            "group_id": "grp-vector-2",
            "epoch": 2,
            "kind": "message",
            "author_fingerprint": "fp-bob",
            "author_device_id": "dev-bob",
            "author_signing_key": "key-bob",
            "author_sequence": 5,
            "previous_author_event": "ev-prev-4",
            "control_head": "ctrl-head-1",
            "hlc_physical_ms": 200000,
            "hlc_logical": 1,
            "target_event_id": "",
            "nonce_base64": "bm9uY2Uy",
            "ciphertext_base64": "Y2lwaGVydGV4dDI=",
            "crypto_suite": "2pchat-epoch-aes256gcm-ed25519-v2",
            "expires_at_ms": 500000,
        },
        {
            "name": "sample_delete_event",
            "group_id": "grp-vector-1",
            "epoch": 1,
            "kind": "delete",
            "author_fingerprint": "fp-alice",
            "author_device_id": "dev-alice",
            "author_signing_key": "key-alice",
            "author_sequence": 2,
            "previous_author_event": "ev-prev-1",
            "control_head": "",
            "hlc_physical_ms": 150000,
            "hlc_logical": 0,
            "target_event_id": "target-msg-to-delete",
            "nonce_base64": "bm9uY2U=",
            "ciphertext_base64": "Y2lwaGVydGV4dA==",
            "crypto_suite": "2pchat-epoch-aes256gcm-ed25519-v1",
            "expires_at_ms": 0,
        },
    ]

    event_id_vectors = []
    for case in event_field_cases:
        canonical, event_id = compute_event_id(case)
        event_id_vectors.append({
            "name": case["name"],
            "canonical_for_signature": canonical,
            "expected_event_id": event_id,
            "event_fields": case,
        })

    payload = {
        "version": 1,
        "roster_hash_vectors": roster_vectors,
        "event_id_vectors": event_id_vectors,
    }

    # Deterministic JSON with indentation and sorted keys
    content = json.dumps(payload, indent=2, sort_keys=True) + "\n"

    # Write target files
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    go_testdata_file = os.path.join(base_dir, "testdata", "group_crypto_test_vectors.json")
    android_res_file = os.path.join(base_dir, "..", "app", "src", "test", "resources", "group_crypto_test_vectors.json")

    os.makedirs(os.path.dirname(go_testdata_file), exist_ok=True)
    os.makedirs(os.path.dirname(android_res_file), exist_ok=True)

    with open(go_testdata_file, "w", encoding="utf-8") as f:
        f.write(content)
    with open(android_res_file, "w", encoding="utf-8") as f:
        f.write(content)

    print(f"Successfully generated vectors to:\n  {go_testdata_file}\n  {android_res_file}")

if __name__ == "__main__":
    main()
