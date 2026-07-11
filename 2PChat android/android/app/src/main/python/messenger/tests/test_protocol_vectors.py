import base64
import json
from pathlib import Path

from nacl.public import PrivateKey
from nacl.signing import SigningKey

from messenger.core import protocol
import messenger.core.double_ratchet as dr_mod
from messenger.core.double_ratchet import (
    IdentityKeyPair,
    PACKET_VERSION,
    PreKeyBundle,
    decrypt_message as dr_decrypt_message,
    encrypt_message as dr_encrypt_message,
    initialize_session_from_prekey,
    respond_to_prekey_init,
)
from messenger.core.identity import fingerprint
from messenger.core.session import Session


FIXTURE_PATH = Path(__file__).with_name("fixtures") / "protocol_vectors.json"


def _load_vectors():
    return json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))


def _build_reference_session(
    identity_priv: PrivateKey,
    prekey_priv: PrivateKey,
    signing_key: SigningKey,
    *,
    bootstrap_eph_priv: PrivateKey | None = None,
):
    session = Session.__new__(Session)
    session.my_priv = identity_priv
    session.my_pub = identity_priv.public_key
    session.prekey_priv = prekey_priv
    session.prekey_pub = prekey_priv.public_key
    session.bootstrap_eph_priv = bootstrap_eph_priv or PrivateKey.generate()
    session.bootstrap_eph_pub = session.bootstrap_eph_priv.public_key
    session.my_signing = signing_key
    session.my_verify = signing_key.verify_key
    return session


def test_protocol_vectors_v3_handshake_matches_fixture():
    vectors = _load_vectors()
    identity_priv = PrivateKey(bytes.fromhex(vectors["identity"]["identity_seed_hex"]))
    prekey_priv = PrivateKey(bytes.fromhex(vectors["identity"]["prekey_seed_hex"]))
    signing_key = SigningKey(bytes.fromhex(vectors["identity"]["signing_seed_hex"]))
    bootstrap_eph_priv = PrivateKey(bytes.fromhex(vectors["identity"]["bootstrap_eph_seed_hex"]))

    v3_session = _build_reference_session(
        identity_priv,
        prekey_priv,
        signing_key,
        bootstrap_eph_priv=bootstrap_eph_priv,
    )

    assert fingerprint(identity_priv.public_key) == vectors["identity"]["fingerprint_base64"]
    assert Session._x3dh_payload(v3_session, "init").decode("utf-8") == vectors["x3dh_handshake"]["init_payload_utf8"]

    x3dh = Session._parse_x3dh_handshake(
        base64.b64decode(vectors["x3dh_handshake"]["init_payload_base64"])
    )

    assert base64.b64encode(bytes(x3dh.identity_pub)).decode() == vectors["identity"]["identity_public_base64"]
    assert base64.b64encode(bytes(x3dh.signed_prekey_pub)).decode() == vectors["identity"]["prekey_public_base64"]
    assert base64.b64encode(bytes(x3dh.ephemeral_pub)).decode() == vectors["identity"]["bootstrap_eph_public_base64"]


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


def test_protocol_vectors_double_ratchet_packet_matches_fixture(monkeypatch):
    vectors = _load_vectors()
    alice_identity_priv = PrivateKey(bytes.fromhex(vectors["identity"]["identity_seed_hex"]))
    bob_identity_priv = PrivateKey(bytes.fromhex(vectors["identity"]["peer_seed_hex"]))
    bob_prekey_priv = PrivateKey(bytes.fromhex(vectors["identity"]["peer_prekey_seed_hex"]))
    alice_bootstrap_priv = PrivateKey(bytes.fromhex(vectors["identity"]["bootstrap_eph_seed_hex"]))
    nonce = bytes.fromhex(vectors["double_ratchet_chat"]["nonce_hex"])
    chat_object = vectors["messages"]["chat_object"]
    plaintext = protocol.encode_message(chat_object, "json")

    alice_signing = SigningKey(bytes.fromhex(vectors["identity"]["signing_seed_hex"]))
    bob_signing = SigningKey(bytes.fromhex(vectors["identity"]["peer_signing_seed_hex"]))

    alice_identity = IdentityKeyPair(
        public=alice_identity_priv.public_key,
        private=alice_identity_priv,
        signing=alice_signing,
    )
    bob_identity = IdentityKeyPair(
        public=bob_identity_priv.public_key,
        private=bob_identity_priv,
        signing=bob_signing,
    )
    bundle = PreKeyBundle(
        identity_pub=bob_identity.public,
        identity_verify_pub=bob_signing.verify_key,
        signed_prekey_pub=bob_prekey_priv.public_key,
        signed_prekey_sig=dr_mod._sign_prekey(bob_signing, bob_prekey_priv.public_key),
    )
    alice_ephemeral = IdentityKeyPair(
        public=alice_bootstrap_priv.public_key,
        private=alice_bootstrap_priv,
        signing=alice_signing,
    )

    monkeypatch.setattr(dr_mod, "nacl_random", lambda size: nonce)

    alice_session = initialize_session_from_prekey(alice_identity, bundle, alice_ephemeral)
    bob_session = respond_to_prekey_init(
        local_identity=bob_identity,
        signed_prekey=bob_prekey_priv,
        local_one_time_prekey=None,
        initiator_identity_pub=alice_identity.public,
        initiator_ephemeral_pub=alice_ephemeral.public,
    )
    packet = dr_encrypt_message(alice_session, plaintext)

    assert packet[0] == PACKET_VERSION
    assert packet.hex() == vectors["double_ratchet_chat"]["packet_hex"]

    decrypted = dr_decrypt_message(bob_session, packet)
    assert decrypted.decode("utf-8") == vectors["double_ratchet_chat"]["decrypted_json_utf8"]
