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
