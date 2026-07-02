from messenger.core.double_ratchet import (
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
    # Force obfuscation flag to verify it is present and plaintext header is hidden
    alice_session.obfuscate_header = True
    packet = encrypt_message(alice_session, b"secret")

    # version + flags
    assert packet[0] == 1
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

