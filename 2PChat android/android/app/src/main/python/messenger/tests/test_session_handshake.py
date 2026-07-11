import base64
import json

import pytest
from nacl.public import PrivateKey, PublicKey
from nacl.signing import SigningKey, VerifyKey

from messenger.core.session import (
    PROTOCOL_V3,
    SIGNED_PREKEY_CONTEXT,
    X3DH_HANDSHAKE_CONTEXT,
    Session,
)


def _make_x3dh_handshake(
    signing: SigningKey,
    identity: PublicKey,
    signed_prekey: PublicKey,
    *,
    role: str,
    eph: PublicKey | None = None,
) -> bytes:
    verify_b = bytes(signing.verify_key)
    identity_b = bytes(identity)
    prekey_b = bytes(signed_prekey)
    eph_b = bytes(eph) if eph is not None else b""
    prekey_sig = signing.sign(SIGNED_PREKEY_CONTEXT + prekey_b).signature
    sig = signing.sign(
        X3DH_HANDSHAKE_CONTEXT + role.encode("ascii") + identity_b + verify_b + prekey_b + eph_b
    ).signature
    payload = {
        "type": "handshake",
        "version": PROTOCOL_V3,
        "role": role,
        "identityPub": base64.b64encode(identity_b).decode(),
        "verifyPub": base64.b64encode(verify_b).decode(),
        "signedPrekeyPub": base64.b64encode(prekey_b).decode(),
        "prekeySignature": base64.b64encode(prekey_sig).decode(),
        "signature": base64.b64encode(sig).decode(),
    }
    if eph is not None:
        payload["ephPub"] = base64.b64encode(eph_b).decode()
    return json.dumps(payload).encode()


def test_x3dh_handshake_init_validates():
    signing = SigningKey.generate()
    identity = PrivateKey.generate().public_key
    signed_prekey = PrivateKey.generate().public_key
    eph = PrivateKey.generate().public_key

    parsed = Session._parse_x3dh_handshake(
        _make_x3dh_handshake(
            signing,
            identity,
            signed_prekey,
            role="init",
            eph=eph,
        )
    )
    assert parsed.role == "init"
    assert parsed.identity_pub == identity
    assert parsed.signed_prekey_pub == signed_prekey
    assert parsed.ephemeral_pub == eph
    assert isinstance(parsed.verify_key, VerifyKey)


def test_x3dh_handshake_tamper_rejected():
    signing = SigningKey.generate()
    identity = PrivateKey.generate().public_key
    signed_prekey = PrivateKey.generate().public_key
    eph = PrivateKey.generate().public_key
    blob = _make_x3dh_handshake(signing, identity, signed_prekey, role="init", eph=eph)
    obj = json.loads(blob.decode())
    obj["prekeySignature"] = base64.b64encode(b"0" * 64).decode()
    tampered = json.dumps(obj).encode()
    with pytest.raises(ValueError, match="Invalid signed handshake payload"):
        Session._parse_x3dh_handshake(tampered)
