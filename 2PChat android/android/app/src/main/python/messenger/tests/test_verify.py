import json

from nacl.public import PrivateKey

from messenger.core import identity
from messenger.core.verify import (
    build_identity_qr_payload,
    parse_identity_qr_payload,
    verify_identity_payload,
)
from messenger.utils.qr import render_qr_ascii


def _fingerprints():
    key = PrivateKey.generate()
    return (
        identity.fingerprint(key.public_key),
        identity.fingerprint(key.public_key, encoding="hex"),
    )


def test_build_parse_roundtrip():
    fp_b64, fp_hex = _fingerprints()
    payload = build_identity_qr_payload(fp_b64, user_label="alice")
    parsed = parse_identity_qr_payload(payload)

    assert parsed["fingerprint"] == fp_hex.lower()
    assert parsed["fingerprint_b64"] == fp_b64
    assert parsed["user_label"] == "alice"


def test_render_qr_ascii_nonempty():
    fp_b64, _ = _fingerprints()
    payload = build_identity_qr_payload(fp_b64)
    ascii_qr = render_qr_ascii(payload)

    assert isinstance(ascii_qr, str)
    assert ascii_qr.strip()


def test_verify_identity_success(tmp_path, monkeypatch):
    monkeypatch.setenv(identity.CONFIG_ENV, str(tmp_path))
    trust = identity.TrustStore(str(tmp_path / "trust.json"))
    fp_base64, _ = _fingerprints()
    payload = build_identity_qr_payload(fp_base64, user_label="ally")

    assert verify_identity_payload(payload, fp_base64, trust, label=None)
    assert trust.state_for(fp_base64) == "verified"
    assert trust.records[fp_base64].label == "ally"


def test_verify_identity_mismatch(tmp_path, monkeypatch):
    monkeypatch.setenv(identity.CONFIG_ENV, str(tmp_path))
    trust = identity.TrustStore(str(tmp_path / "trust.json"))
    fp_base64, _ = _fingerprints()
    other_fp_base64, _ = _fingerprints()
    payload = build_identity_qr_payload(other_fp_base64)

    assert not verify_identity_payload(payload, fp_base64, trust)
    assert trust.state_for(fp_base64) is None
    assert trust.state_for(other_fp_base64) is None


def test_payload_contains_only_public_fields():
    fp_b64, fp_hex = _fingerprints()
    payload = build_identity_qr_payload(fp_b64)
    data = json.loads(payload)

    assert set(data.keys()).issubset(
        {
            "type",
            "version",
            "fingerprint",
            "fingerprint_b64",
            "algorithm",
            "user_label",
        }
    )
    assert "private" not in payload
