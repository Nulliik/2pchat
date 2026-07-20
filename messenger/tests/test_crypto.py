import pytest
from nacl.public import PrivateKey

from messenger.core import crypto


def test_hkdf_sha256_rfc5869_multiblock_vector():
    output = crypto.hkdf_sha256(
        bytes.fromhex("0b" * 22),
        salt=bytes.fromhex("000102030405060708090a0b0c"),
        info=bytes.fromhex("f0f1f2f3f4f5f6f7f8f9"),
        length=42,
    )
    assert output.hex() == (
        "3cb25f25faacd57a90434f64d0362f2a"
        "2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
        "34007208d5b887185865"
    )


def test_meshtastic_style_encrypt_decrypt():
    alice_pub, alice_priv = crypto.generate_identity_keypair()
    bob_pub, bob_priv = crypto.generate_identity_keypair()

    alice_state = crypto.PeerState()
    bob_state = crypto.PeerState()

    bob_prekey_priv = PrivateKey.generate()
    bob_prekey_pub = bob_prekey_priv.public_key

    packet = crypto.encrypt_message(
        alice_priv,
        bob_pub,
        alice_state,
        b"hello",
        their_prekey_pub=bob_prekey_pub,
    )
    plaintext = crypto.decrypt_message(
        bob_priv,
        alice_pub,
        bob_state,
        packet,
        my_prekey_priv=bob_prekey_priv,
    )

    assert plaintext == b"hello"

    # Replay should be rejected
    try:
        crypto.decrypt_message(
            bob_priv, alice_pub, bob_state, packet, my_prekey_priv=bob_prekey_priv
        )
    except ValueError as exc:
        assert "replay" in str(exc)
    else:  # pragma: no cover - defensive
        raise AssertionError("replay not detected")


def test_replay_window_accepts_out_of_order_packets_once():
    alice_pub, alice_priv = crypto.generate_identity_keypair()
    bob_pub, bob_priv = crypto.generate_identity_keypair()
    alice_state = crypto.PeerState()
    bob_state = crypto.PeerState()
    bob_prekey_priv = PrivateKey.generate()

    packets = [
        crypto.encrypt_message(
            alice_priv,
            bob_pub,
            alice_state,
            body,
            their_prekey_pub=bob_prekey_priv.public_key,
        )
        for body in (b"one", b"two", b"three")
    ]

    assert crypto.decrypt_message(
        bob_priv, alice_pub, bob_state, packets[2], my_prekey_priv=bob_prekey_priv
    ) == b"three"
    assert crypto.decrypt_message(
        bob_priv, alice_pub, bob_state, packets[0], my_prekey_priv=bob_prekey_priv
    ) == b"one"
    assert crypto.decrypt_message(
        bob_priv, alice_pub, bob_state, packets[1], my_prekey_priv=bob_prekey_priv
    ) == b"two"
    with pytest.raises(ValueError, match="replay"):
        crypto.decrypt_message(
            bob_priv, alice_pub, bob_state, packets[0], my_prekey_priv=bob_prekey_priv
        )


def test_forged_high_counter_does_not_advance_replay_window():
    alice_pub, alice_priv = crypto.generate_identity_keypair()
    bob_pub, bob_priv = crypto.generate_identity_keypair()
    alice_state = crypto.PeerState()
    bob_state = crypto.PeerState()
    bob_prekey_priv = PrivateKey.generate()
    packet = crypto.encrypt_message(
        alice_priv,
        bob_pub,
        alice_state,
        b"valid",
        their_prekey_pub=bob_prekey_priv.public_key,
    )
    forged = bytearray(packet)
    forged[1:9] = (10_000).to_bytes(8, "big")

    with pytest.raises(Exception):
        crypto.decrypt_message(
            bob_priv, alice_pub, bob_state, bytes(forged), my_prekey_priv=bob_prekey_priv
        )
    assert bob_state.recv_highest_counter == -1
    assert crypto.decrypt_message(
        bob_priv, alice_pub, bob_state, packet, my_prekey_priv=bob_prekey_priv
    ) == b"valid"


def test_prekey_required_for_encrypt_and_decrypt():
    alice_pub, alice_priv = crypto.generate_identity_keypair()
    bob_pub, bob_priv = crypto.generate_identity_keypair()

    alice_state = crypto.PeerState()
    bob_state = crypto.PeerState()

    with pytest.raises(TypeError):
        crypto.encrypt_message(alice_priv, bob_pub, alice_state, b"hello")

    bob_prekey_priv = PrivateKey.generate()
    bob_prekey_pub = bob_prekey_priv.public_key
    packet = crypto.encrypt_message(
        alice_priv,
        bob_pub,
        alice_state,
        b"hello",
        their_prekey_pub=bob_prekey_pub,
    )

    with pytest.raises(TypeError):
        crypto.decrypt_message(bob_priv, alice_pub, bob_state, packet)
