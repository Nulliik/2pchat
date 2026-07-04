import base64
import json
from pathlib import Path

from nacl.public import PrivateKey
from nacl.signing import SigningKey

from messenger.core import protocol
import messenger.core.crypto as crypto_mod
from messenger.core.crypto import PeerState, decrypt_message, encrypt_message
from messenger.core.identity import fingerprint
from messenger.core.session import Session


FIXTURE_PATH = Path(__file__).with_name("fixtures") / "protocol_vectors.json"


def _load_vectors():
    return json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))


def _build_reference_session(
    identity_priv: PrivateKey,
    prekey_priv: PrivateKey,
    signing_key: SigningKey,
):
    session = Session.__new__(Session)
    session.my_priv = identity_priv
    session.my_pub = identity_priv.public_key
    session.prekey_priv = prekey_priv
    session.prekey_pub = prekey_priv.public_key
    session.my_signing = signing_key
    session.my_verify = signing_key.verify_key
    return session


def test_protocol_vectors_identity_and_handshake_match_fixture():
    vectors = _load_vectors()
    identity_priv = PrivateKey(bytes.fromhex(vectors["identity"]["identity_seed_hex"]))
    prekey_priv = PrivateKey(bytes.fromhex(vectors["identity"]["prekey_seed_hex"]))
    signing_key = SigningKey(bytes.fromhex(vectors["identity"]["signing_seed_hex"]))
    session = _build_reference_session(identity_priv, prekey_priv, signing_key)

    assert fingerprint(identity_priv.public_key) == vectors["identity"]["fingerprint_base64"]
    assert (
        Session._handshake_payload(session).decode("utf-8")
        == vectors["handshake"]["payload_utf8"]
    )
    parsed = Session._parse_handshake(
        base64.b64decode(vectors["handshake"]["payload_base64"])
    )
    their_pub, their_verify, their_prekey = parsed
    assert (
        base64.b64encode(bytes(their_pub)).decode()
        == vectors["identity"]["identity_public_base64"]
    )
    assert (
        base64.b64encode(bytes(their_verify)).decode()
        == vectors["identity"]["verify_public_base64"]
    )
    assert (
        base64.b64encode(bytes(their_prekey)).decode()
        == vectors["identity"]["prekey_public_base64"]
    )


def test_protocol_vectors_json_and_cbor_match_fixture():
    vectors = _load_vectors()
    chat_object = vectors["messages"]["chat_object"]

    json_payload = protocol.encode_message(chat_object, "json")
    cbor_payload = protocol.encode_message(chat_object, "cbor")

    assert json_payload.decode("utf-8") == vectors["messages"]["json_utf8"]
    assert json_payload.hex() == vectors["messages"]["json_hex"]
    assert cbor_payload.hex() == vectors["messages"]["cbor_hex"]
    assert protocol.decode_message(json_payload, "json") == chat_object
    assert protocol.decode_message(cbor_payload, "cbor") == chat_object


def test_protocol_vectors_message_refs_cover_chat_and_ack():
    vectors = _load_vectors()

    assert Session._message_ref(vectors["messages"]["chat_object"]) == "id=msg-42"
    assert Session._message_ref(vectors["messages"]["ack_object"]) == "ack_id=msg-42"


def test_protocol_vectors_encrypted_packet_matches_fixture(monkeypatch):
    vectors = _load_vectors()
    identity_priv = PrivateKey(bytes.fromhex(vectors["identity"]["identity_seed_hex"]))
    peer_priv = PrivateKey(bytes.fromhex(vectors["identity"]["peer_seed_hex"]))
    prekey_priv = PrivateKey(bytes.fromhex(vectors["identity"]["prekey_seed_hex"]))
    ephemeral_priv = PrivateKey(bytes.fromhex(vectors["encrypted_chat"]["ephemeral_seed_hex"]))
    nonce = bytes.fromhex(vectors["encrypted_chat"]["nonce_hex"])
    chat_object = vectors["messages"]["chat_object"]
    plaintext = protocol.encode_message(chat_object, "json")

    monkeypatch.setattr(crypto_mod.PrivateKey, "generate", lambda: ephemeral_priv)
    monkeypatch.setattr(crypto_mod, "nacl_random", lambda size: nonce)

    sender_state = PeerState()
    packet = encrypt_message(
        identity_priv,
        peer_priv.public_key,
        sender_state,
        plaintext,
        their_prekey_pub=prekey_priv.public_key,
    )

    assert packet.hex() == vectors["encrypted_chat"]["packet_hex"]

    receiver_state = PeerState()
    decrypted = decrypt_message(
        peer_priv,
        identity_priv.public_key,
        receiver_state,
        packet,
        my_prekey_priv=prekey_priv,
    )
    assert decrypted.decode("utf-8") == vectors["encrypted_chat"]["decrypted_json_utf8"]
