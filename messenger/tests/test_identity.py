import pytest

from messenger.app import cli_chat
from messenger.core import identity
from nacl.encoding import Base64Encoder
from nacl.public import PrivateKey
from nacl.signing import VerifyKey


class _FakeKeystore:
    @staticmethod
    def encrypt(value):
        return value[::-1]

    @staticmethod
    def decrypt(value):
        return value[::-1]


def test_identity_is_encrypted_and_plaintext_is_migrated(monkeypatch, tmp_path):
    monkeypatch.setattr(identity, "_android_keystore", lambda: _FakeKeystore)
    path = tmp_path / "identity.key"
    original = PrivateKey.generate()
    plaintext = original.encode(Base64Encoder).decode("ascii")
    path.write_text(plaintext)

    loaded = identity.load_or_create_identity(str(path))

    assert bytes(loaded) == bytes(original)
    stored = path.read_text()
    assert stored.startswith("android-keystore-v1:")
    assert plaintext not in stored
    assert bytes(identity.load_or_create_identity(str(path))) == bytes(original)


def test_load_or_create_identity(tmp_path, monkeypatch):
    monkeypatch.setenv(identity.CONFIG_ENV, str(tmp_path))
    key_path = tmp_path / "id.key"
    priv1 = identity.load_or_create_identity(str(key_path))
    priv2 = identity.load_or_create_identity(str(key_path))
    assert priv1.encode() == priv2.encode()


def test_load_or_create_signing_identity(tmp_path, monkeypatch):
    monkeypatch.setenv(identity.CONFIG_ENV, str(tmp_path))
    key_path = tmp_path / "sign.key"
    sk1 = identity.load_or_create_signing_identity(str(key_path))
    sk2 = identity.load_or_create_signing_identity(str(key_path))
    assert sk1.encode() == sk2.encode()
    assert isinstance(sk1.verify_key, VerifyKey)


def test_trust_store_records_and_labels(tmp_path, monkeypatch):
    monkeypatch.setenv(identity.CONFIG_ENV, str(tmp_path))
    store_path = tmp_path / "trust.json"
    store = identity.TrustStore(str(store_path))
    fp = identity.fingerprint(
        identity.load_or_create_identity(str(tmp_path / "a.key")).public_key
    )

    status_new = store.note_peer(fp, timestamp=1.0, label="peer")
    status_known = store.note_peer(fp, timestamp=2.0)

    assert status_new.state == "new"
    assert status_known.state == "known"
    assert store.label_for(fp) == "peer"
    assert status_new.warning is None


def test_label_conflict_warning(tmp_path, monkeypatch):
    monkeypatch.setenv(identity.CONFIG_ENV, str(tmp_path))
    store = identity.TrustStore(str(tmp_path / "trust.json"))
    fp_a = identity.fingerprint(
        identity.load_or_create_identity(str(tmp_path / "a.key")).public_key
    )
    fp_b = identity.fingerprint(
        identity.load_or_create_identity(str(tmp_path / "b.key")).public_key
    )

    store.note_peer(fp_a, timestamp=1.0, label="friend")
    status = store.note_peer(fp_b, timestamp=2.0, label="friend")

    assert status.warning is not None


def test_expected_fingerprint_rejects(tmp_path, monkeypatch):
    monkeypatch.setenv(identity.CONFIG_ENV, str(tmp_path))
    store = identity.TrustStore(str(tmp_path / "trust.json"))
    fp_a = identity.fingerprint(
        identity.load_or_create_identity(str(tmp_path / "a.key")).public_key
    )
    fp_b = identity.fingerprint(
        identity.load_or_create_identity(str(tmp_path / "b.key")).public_key
    )
    store.expected_or_raise(fp_a, expected=fp_a)
    try:
        store.expected_or_raise(fp_a, expected=fp_b)
    except ValueError:
        return
    assert False, "Expected mismatch to raise"


def test_mark_verified(tmp_path, monkeypatch):
    monkeypatch.setenv(identity.CONFIG_ENV, str(tmp_path))
    store = identity.TrustStore(str(tmp_path / "trust.json"))
    fp = identity.fingerprint(
        identity.load_or_create_identity(str(tmp_path / "peer.key")).public_key
    )
    store.note_peer(fp, timestamp=1.0)
    status = store.mark_verified(fp, timestamp=2.0, label="friend")

    assert status.state == "verified"
    assert store.records[fp].state == "verified"
    assert store.label_for(fp) == "friend"


def test_outbox_roundtrip(tmp_path, monkeypatch):
    monkeypatch.setenv(identity.CONFIG_ENV, str(tmp_path))
    outbox = identity.Outbox(str(tmp_path / "queue.json"))

    msg = outbox.add_chat("hi", timestamp=1.0, nickname="Neo", peer_fp="peer")
    assert msg in list(outbox.pending())
    assert msg["nickname"] == "Neo"
    assert msg["peer_fp"] == "peer"

    outbox.mark_sent(msg["id"])
    assert list(outbox.pending()) == []


class _DummySession:
    def __init__(self, fingerprint: str):
        self.peer_fingerprint = fingerprint
        self.sent = []

    async def send_reliable(self, message):
        self.sent.append(message)


@pytest.mark.asyncio
async def test_outbox_skips_mismatched_peer(tmp_path, monkeypatch):
    monkeypatch.setenv(identity.CONFIG_ENV, str(tmp_path))
    outbox = identity.Outbox(str(tmp_path / "queue.json"))
    matched = outbox.add_chat("ok", timestamp=1.0, peer_fp="fp1", nickname=None)
    mismatched = outbox.add_chat("no", timestamp=2.0, peer_fp="fp2", nickname=None)
    legacy = outbox.add_chat("legacy", timestamp=3.0, peer_fp=None, nickname=None)

    session = _DummySession("fp1")
    await cli_chat._flush_outbox(session, outbox)

    assert matched["id"] not in {msg["id"] for msg in outbox.pending()}
    assert mismatched in list(outbox.pending())
    assert legacy in list(outbox.pending())
    assert any(msg["id"] == matched["id"] for msg in session.sent)
