-- Frozen TwoPChat Group Chat SQLite Schema v6
PRAGMA user_version = 6;

CREATE TABLE groups(
    group_id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    avatar_uri TEXT,
    local_device_id TEXT NOT NULL,
    owner_device_id TEXT NOT NULL,
    current_epoch INTEGER NOT NULL DEFAULT 0,
    control_head TEXT,
    pinned_event_id TEXT,
    metadata_version INTEGER NOT NULL DEFAULT 0,
    unread_count INTEGER NOT NULL DEFAULT 0 CHECK(unread_count >= 0),
    admin_only_posting INTEGER NOT NULL DEFAULT 0 CHECK(admin_only_posting IN (0, 1)),
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL
);

CREATE TABLE group_members(
    group_id TEXT NOT NULL,
    device_id TEXT NOT NULL,
    account_id TEXT NOT NULL,
    display_name TEXT NOT NULL,
    transport_fingerprint TEXT NOT NULL DEFAULT '',
    peer_name TEXT NOT NULL,
    signing_key_base64 TEXT NOT NULL DEFAULT '',
    role TEXT NOT NULL,
    permissions INTEGER NOT NULL,
    status TEXT NOT NULL,
    joined_epoch INTEGER NOT NULL,
    removed_epoch INTEGER,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    PRIMARY KEY(group_id, device_id),
    FOREIGN KEY(group_id) REFERENCES groups(group_id) ON DELETE CASCADE
);

CREATE TABLE group_epoch_keys(
    group_id TEXT NOT NULL,
    epoch INTEGER NOT NULL,
    key_material BLOB NOT NULL,
    created_at_ms INTEGER NOT NULL,
    expires_at_ms INTEGER,
    PRIMARY KEY(group_id, epoch),
    FOREIGN KEY(group_id) REFERENCES groups(group_id) ON DELETE CASCADE
);

CREATE TABLE group_events(
    group_id TEXT NOT NULL,
    event_id TEXT NOT NULL,
    epoch INTEGER NOT NULL,
    author_device_id TEXT NOT NULL,
    author_seq INTEGER NOT NULL CHECK(author_seq >= 0),
    hlc_physical_ms INTEGER NOT NULL,
    hlc_logical INTEGER NOT NULL CHECK(hlc_logical >= 0),
    kind TEXT NOT NULL,
    body TEXT,
    target_event_id TEXT,
    control_head TEXT,
    payload BLOB,
    created_at_ms INTEGER NOT NULL,
    received_at_ms INTEGER NOT NULL,
    PRIMARY KEY(group_id, event_id),
    UNIQUE(group_id, author_device_id, author_seq),
    FOREIGN KEY(group_id) REFERENCES groups(group_id) ON DELETE CASCADE
);

CREATE TABLE group_messages(
    group_id TEXT NOT NULL,
    message_id TEXT NOT NULL,
    author_device_id TEXT NOT NULL,
    author_seq INTEGER NOT NULL,
    hlc_physical_ms INTEGER NOT NULL,
    hlc_logical INTEGER NOT NULL,
    body TEXT NOT NULL,
    edited INTEGER NOT NULL DEFAULT 0,
    deleted INTEGER NOT NULL DEFAULT 0,
    unread INTEGER NOT NULL DEFAULT 0,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    PRIMARY KEY(group_id, message_id),
    FOREIGN KEY(group_id, message_id)
        REFERENCES group_events(group_id, event_id) ON DELETE CASCADE
);

CREATE TABLE outbox_tasks(
    task_id TEXT PRIMARY KEY,
    group_id TEXT NOT NULL,
    event_id TEXT NOT NULL,
    recipient_device_id TEXT NOT NULL,
    payload BLOB NOT NULL,
    state TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_ms INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL
);

CREATE TABLE receipts(
    group_id TEXT NOT NULL,
    event_id TEXT NOT NULL,
    recipient_device_id TEXT NOT NULL,
    type TEXT NOT NULL,
    received_at_ms INTEGER NOT NULL,
    PRIMARY KEY(group_id, event_id, recipient_device_id, type)
);

CREATE TABLE pending_invites(
    invite_id TEXT PRIMARY KEY,
    group_id TEXT NOT NULL,
    inviter_device_id TEXT NOT NULL,
    target_device_id TEXT NOT NULL,
    target_fingerprint TEXT NOT NULL,
    target_peer_name TEXT NOT NULL,
    epoch INTEGER NOT NULL,
    status TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL
);

CREATE TABLE sync_cursors(
    group_id TEXT NOT NULL,
    author_device_id TEXT NOT NULL,
    last_author_sequence INTEGER NOT NULL,
    updated_at_ms INTEGER NOT NULL,
    PRIMARY KEY(group_id, author_device_id)
);

CREATE TABLE owner_lineage_certificates(
    group_id TEXT NOT NULL,
    epoch INTEGER NOT NULL,
    certificate_id TEXT NOT NULL,
    owner_device_id TEXT NOT NULL,
    owner_signing_key TEXT NOT NULL,
    signature TEXT NOT NULL,
    created_at_ms INTEGER NOT NULL,
    PRIMARY KEY(group_id, epoch)
);

CREATE TABLE roster_snapshot_pages(
    group_id TEXT NOT NULL,
    page_index INTEGER NOT NULL,
    total_pages INTEGER NOT NULL,
    snapshot_data BLOB NOT NULL,
    created_at_ms INTEGER NOT NULL,
    PRIMARY KEY(group_id, page_index)
);

CREATE TABLE group_reactions(
    group_id TEXT NOT NULL,
    target_event_id TEXT NOT NULL,
    emoji TEXT NOT NULL,
    author_device_id TEXT NOT NULL,
    author_seq INTEGER NOT NULL,
    created_at_ms INTEGER NOT NULL,
    PRIMARY KEY(group_id, target_event_id, emoji, author_device_id)
);

CREATE INDEX idx_group_events_author_seq ON group_events(group_id, author_device_id, author_seq);
CREATE INDEX idx_group_events_target ON group_events(group_id, target_event_id);
CREATE INDEX idx_group_messages_timeline ON group_messages(group_id, hlc_physical_ms, hlc_logical, author_device_id, author_seq, message_id);
CREATE INDEX idx_outbox_due ON outbox_tasks(state, next_attempt_ms, created_at_ms);
CREATE INDEX idx_group_members_group_status ON group_members(group_id, status);
CREATE INDEX idx_group_events_kind ON group_events(group_id, kind);
CREATE INDEX idx_receipts_lookup ON receipts(group_id, event_id, type);
CREATE INDEX idx_group_events_lookup ON group_events(group_id, event_id);
