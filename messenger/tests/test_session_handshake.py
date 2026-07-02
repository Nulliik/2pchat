import base64
import json

import pytest
from nacl.public import PrivateKey, PublicKey
from nacl.signing import SigningKey, VerifyKey

from messenger.core.session import HANDSHAKE_CONTEXT, Session


def _make_handshake(signing: SigningKey, eph: PublicKey, prekey: PublicKey | None = None) -> bytes:
    eph_b = bytes(eph)
    prekey_b = bytes(prekey or eph)
    id_b = bytes(signing.verify_key)
    sig = signing.sign(HANDSHAKE_CONTEXT + eph_b + prekey_b + id_b).signature
    payload = {
        "type": "handshake",
        "version": 2,
        "ephPub": base64.b64encode(eph_b).decode(),
        "prekeyPub": base64.b64encode(prekey_b).decode(),
        "identityPub": base64.b64encode(id_b).decode(),
        "signature": base64.b64encode(sig).decode(),
    }
    return json.dumps(payload).encode()


def test_handshake_signature_validates():
    signing = SigningKey.generate()
    eph = PrivateKey.generate().public_key
    prekey = PrivateKey.generate().public_key
    peer_pub, verify, their_prekey = Session._parse_handshake(
        _make_handshake(signing, eph, prekey)
    )
    assert peer_pub == eph
    assert their_prekey == prekey
    assert isinstance(verify, VerifyKey)


def test_handshake_signature_tamper_rejected():
    signing = SigningKey.generate()
    eph = PrivateKey.generate().public_key
    prekey = PrivateKey.generate().public_key
    blob = _make_handshake(signing, eph, prekey)
    obj = json.loads(blob.decode())
    obj["signature"] = base64.b64encode(b"0" * 64).decode()
    tampered = json.dumps(obj).encode()
    with pytest.raises(Exception):
        Session._parse_handshake(tampered)


def test_unsigned_legacy_handshake_rejected():
    with pytest.raises(ValueError, match="Invalid signed handshake payload"):
        Session._parse_handshake(b"0" * 32)
