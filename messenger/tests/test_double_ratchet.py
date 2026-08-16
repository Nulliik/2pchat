from messenger.core.double_ratchet import (
    PACKET_VERSION,
    HEADER_FLAG_OBFUSCATED,
    IdentityKeyPair,
    PreKeyBundle,
    decrypt_message,
    encrypt_message,
    initialize_session_from_prekey,
    respond_to_prekey_init,
    _sign_prekey,
    safety_number,
)
from nacl.public import PrivateKey
import copy
import pytest


def test_double_ratchet_round_trip():
    alice = IdentityKeyPair.generate()
    bob = IdentityKeyPair.generate()

    bob_signed_prekey = PrivateKey.generate()
    bob_one_time = PrivateKey.generate()
    bundle = PreKeyBundle(
        identity_pub=bob.public,
        identity_verify_pub=bob.signing.verify_key,
        signed_prekey_pub=bob_signed_prekey.public_key,
        signed_prekey_sig=_sign_prekey(bob.signing, bob_signed_prekey.public_key),
        one_time_prekey_pub=bob_one_time.public_key,
    )

    alice_ephemeral = IdentityKeyPair.generate()
    alice_session = initialize_session_from_prekey(alice, bundle, alice_ephemeral)
    bob_session = respond_to_prekey_init(
        local_identity=bob,
        signed_prekey=bob_signed_prekey,
        local_one_time_prekey=bob_one_time,
        initiator_identity_pub=alice.public,
        initiator_ephemeral_pub=alice_ephemeral.public,
    )

    packets = [encrypt_message(alice_session, b"one"), encrypt_message(alice_session, b"two")]
    assert decrypt_message(bob_session, packets[0]) == b"one"
    assert decrypt_message(bob_session, packets[1]) == b"two"

    bob_packets = [encrypt_message(bob_session, b"alpha"), encrypt_message(bob_session, b"beta")]
    assert decrypt_message(alice_session, bob_packets[1]) == b"beta"
    assert decrypt_message(alice_session, bob_packets[0]) == b"alpha"

    assert len(safety_number(alice.public, bob.public)) == 60


def test_replay_rejected():
    alice = IdentityKeyPair.generate()
    bob = IdentityKeyPair.generate()
    signed_prekey = PrivateKey.generate()
    bundle = PreKeyBundle(
        identity_pub=bob.public,
        identity_verify_pub=bob.signing.verify_key,
        signed_prekey_pub=signed_prekey.public_key,
        signed_prekey_sig=_sign_prekey(bob.signing, signed_prekey.public_key),
    )
    eph = IdentityKeyPair.generate()
    alice_session = initialize_session_from_prekey(alice, bundle, eph)
    bob_session = respond_to_prekey_init(
        local_identity=bob,
        signed_prekey=signed_prekey,
        local_one_time_prekey=None,
        initiator_identity_pub=alice.public,
        initiator_ephemeral_pub=eph.public,
    )

    pkt = encrypt_message(alice_session, b"test")
    assert decrypt_message(bob_session, pkt) == b"test"
    try:
        decrypt_message(bob_session, pkt)
    except ValueError:
        pass
    else:
        raise AssertionError("replay accepted")


def test_header_obfuscation_hides_dh_key():
    alice = IdentityKeyPair.generate()
    bob = IdentityKeyPair.generate()
    signed_prekey = PrivateKey.generate()
    bundle = PreKeyBundle(
        identity_pub=bob.public,
        identity_verify_pub=bob.signing.verify_key,
        signed_prekey_pub=signed_prekey.public_key,
        signed_prekey_sig=_sign_prekey(bob.signing, signed_prekey.public_key),
    )
    eph = IdentityKeyPair.generate()
    alice_session = initialize_session_from_prekey(alice, bundle, eph)
    # Header protection is the protocol default.
    assert alice_session.obfuscate_header is True
    packet = encrypt_message(alice_session, b"secret")

    # version + flags
    assert packet[0] == PACKET_VERSION
    assert packet[1] & HEADER_FLAG_OBFUSCATED

    # The DH public key bytes should not appear at the expected plaintext offset
    dh_bytes = bytes(alice_session.dh_send_key.public_key)
    assert dh_bytes not in packet[2 : 2 + len(dh_bytes)]

    bob_session = respond_to_prekey_init(
        local_identity=bob,
        signed_prekey=signed_prekey,
        local_one_time_prekey=None,
        initiator_identity_pub=alice.public,
        initiator_ephemeral_pub=eph.public,
    )
    assert decrypt_message(bob_session, packet) == b"secret"


def test_header_obfuscation_survives_a_dh_ratchet_reply():
    alice = IdentityKeyPair.generate()
    bob = IdentityKeyPair.generate()
    signed_prekey = PrivateKey.generate()
    bundle = PreKeyBundle(
        identity_pub=bob.public,
        identity_verify_pub=bob.signing.verify_key,
        signed_prekey_pub=signed_prekey.public_key,
        signed_prekey_sig=_sign_prekey(bob.signing, signed_prekey.public_key),
    )
    eph = IdentityKeyPair.generate()
    alice_session = initialize_session_from_prekey(alice, bundle, eph)
    bob_session = respond_to_prekey_init(bob, signed_prekey, None, alice.public, eph.public)

    assert decrypt_message(bob_session, encrypt_message(alice_session, b"hello")) == b"hello"
    reply = encrypt_message(bob_session, b"reply after ratchet")
    assert reply[1] & HEADER_FLAG_OBFUSCATED
    assert decrypt_message(alice_session, reply) == b"reply after ratchet"


def test_forged_header_does_not_mutate_ratchet_state():
    alice = IdentityKeyPair.generate()
    bob = IdentityKeyPair.generate()
    signed_prekey = PrivateKey.generate()
    bundle = PreKeyBundle(
        identity_pub=bob.public,
        identity_verify_pub=bob.signing.verify_key,
        signed_prekey_pub=signed_prekey.public_key,
        signed_prekey_sig=_sign_prekey(bob.signing, signed_prekey.public_key),
    )
    eph = IdentityKeyPair.generate()
    alice_session = initialize_session_from_prekey(alice, bundle, eph)
    bob_session = respond_to_prekey_init(
        bob, signed_prekey, None, alice.public, eph.public
    )
    packet = encrypt_message(alice_session, b"authentic")
    before = copy.copy(bob_session)
    forged = bytearray(packet)
    forged[2:34] = bytes(PrivateKey.generate().public_key)
    with pytest.raises(ValueError):
        decrypt_message(bob_session, bytes(forged))
    assert bob_session.root_key == before.root_key
    assert bob_session.recv_chain_key == before.recv_chain_key
    assert bob_session.recv_idx == before.recv_idx
    assert decrypt_message(bob_session, packet) == b"authentic"


def test_bad_out_of_order_packet_does_not_consume_skipped_key():
    alice = IdentityKeyPair.generate()
    bob = IdentityKeyPair.generate()
    signed_prekey = PrivateKey.generate()
    bundle = PreKeyBundle(
        identity_pub=bob.public,
        identity_verify_pub=bob.signing.verify_key,
        signed_prekey_pub=signed_prekey.public_key,
        signed_prekey_sig=_sign_prekey(bob.signing, signed_prekey.public_key),
    )
    eph = IdentityKeyPair.generate()
    a = initialize_session_from_prekey(alice, bundle, eph)
    b = respond_to_prekey_init(bob, signed_prekey, None, alice.public, eph.public)
    first, second = encrypt_message(a, b"first"), encrypt_message(a, b"second")
    assert decrypt_message(b, second) == b"second"
    forged = bytearray(first)
    forged[-1] ^= 1
    with pytest.raises(ValueError):
        decrypt_message(b, bytes(forged))
    assert decrypt_message(b, first) == b"first"


def test_safety_number_is_order_independent_and_binds_signing_keys():
    alice, bob = IdentityKeyPair.generate(), IdentityKeyPair.generate()
    ab = safety_number(alice.public, bob.public, alice.signing.verify_key, bob.signing.verify_key)
    ba = safety_number(bob.public, alice.public, bob.signing.verify_key, alice.signing.verify_key)
    assert ab == ba
    replacement = IdentityKeyPair.generate()
    assert ab != safety_number(
        alice.public, bob.public, alice.signing.verify_key, replacement.signing.verify_key
    )


def test_scrambled_out_of_order_decryption_with_gap_filling():
    alice = IdentityKeyPair.generate()
    bob = IdentityKeyPair.generate()
    signed_prekey = PrivateKey.generate()
    bundle = PreKeyBundle(
        identity_pub=bob.public,
        identity_verify_pub=bob.signing.verify_key,
        signed_prekey_pub=signed_prekey.public_key,
        signed_prekey_sig=_sign_prekey(bob.signing, signed_prekey.public_key),
    )
    eph = IdentityKeyPair.generate()
    alice_session = initialize_session_from_prekey(alice, bundle, eph)
    bob_session = respond_to_prekey_init(bob, signed_prekey, None, alice.public, eph.public)

    # Alice creates 10 sequential messages
    messages = [f"Message #{i}".encode("utf-8") for i in range(10)]
    packets = [encrypt_message(alice_session, m) for m in messages]

    # Receive in a heavily scrambled order: [9, 3, 7, 0, 2, 8, 1, 5, 4, 6]
    receive_order = [9, 3, 7, 0, 2, 8, 1, 5, 4, 6]
    for idx in receive_order:
        decrypted = decrypt_message(bob_session, packets[idx])
        assert decrypted == messages[idx]


def test_interleaved_conversational_out_of_order_with_ratchet_steps():
    alice = IdentityKeyPair.generate()
    bob = IdentityKeyPair.generate()
    signed_prekey = PrivateKey.generate()
    bundle = PreKeyBundle(
        identity_pub=bob.public,
        identity_verify_pub=bob.signing.verify_key,
        signed_prekey_pub=signed_prekey.public_key,
        signed_prekey_sig=_sign_prekey(bob.signing, signed_prekey.public_key),
    )
    eph = IdentityKeyPair.generate()
    alice_session = initialize_session_from_prekey(alice, bundle, eph)
    bob_session = respond_to_prekey_init(bob, signed_prekey, None, alice.public, eph.public)

    # Turn 1: Alice sends A1, A2, A3
    a1 = encrypt_message(alice_session, b"Alice message 1")
    a2 = encrypt_message(alice_session, b"Alice message 2")
    a3 = encrypt_message(alice_session, b"Alice message 3")

    # Bob receives A3 first (skips A1, A2), then A1, then A2
    assert decrypt_message(bob_session, a3) == b"Alice message 3"
    assert decrypt_message(bob_session, a1) == b"Alice message 1"
    assert decrypt_message(bob_session, a2) == b"Alice message 2"

    # Turn 2: Bob replies B1, B2, B3 (triggers DH ratchet on Bob)
    b1 = encrypt_message(bob_session, b"Bob reply 1")
    b2 = encrypt_message(bob_session, b"Bob reply 2")
    b3 = encrypt_message(bob_session, b"Bob reply 3")

    # Alice receives B2 first (triggers DH ratchet, skips B1), then B3, then B1
    assert decrypt_message(alice_session, b2) == b"Bob reply 2"
    assert decrypt_message(alice_session, b3) == b"Bob reply 3"
    assert decrypt_message(alice_session, b1) == b"Bob reply 1"

    # Turn 3: Alice replies A4, A5 (triggers DH ratchet on Alice)
    a4 = encrypt_message(alice_session, b"Alice reply 4")
    a5 = encrypt_message(alice_session, b"Alice reply 5")

    # Bob receives A5 first (triggers DH ratchet, skips A4), then A4
    assert decrypt_message(bob_session, a5) == b"Alice reply 5"
    assert decrypt_message(bob_session, a4) == b"Alice reply 4"


def test_replay_of_skipped_message_key_rejected():
    alice = IdentityKeyPair.generate()
    bob = IdentityKeyPair.generate()
    signed_prekey = PrivateKey.generate()
    bundle = PreKeyBundle(
        identity_pub=bob.public,
        identity_verify_pub=bob.signing.verify_key,
        signed_prekey_pub=signed_prekey.public_key,
        signed_prekey_sig=_sign_prekey(bob.signing, signed_prekey.public_key),
    )
    eph = IdentityKeyPair.generate()
    alice_session = initialize_session_from_prekey(alice, bundle, eph)
    bob_session = respond_to_prekey_init(bob, signed_prekey, None, alice.public, eph.public)

    p1 = encrypt_message(alice_session, b"first")
    p2 = encrypt_message(alice_session, b"second")

    # Bob receives p2 first, skipping p1
    assert decrypt_message(bob_session, p2) == b"second"

    # Bob receives p1 (uses skipped key)
    assert decrypt_message(bob_session, p1) == b"first"

    # Replay of p1 must fail because its skipped key was consumed and zeroized
    with pytest.raises(ValueError):
        decrypt_message(bob_session, p1)
