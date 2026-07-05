from pathlib import Path

import pytest
from nacl.public import PrivateKey

from messenger.core.crypto import (
    PeerState,
    decrypt_file_chunks,
    generate_identity_keypair,
    receive_encrypted_file,
    send_encrypted_file,
)


@pytest.fixture()
def keypairs():
    alice_pub, alice_priv = generate_identity_keypair()
    bob_pub, bob_priv = generate_identity_keypair()
    bob_prekey_priv = PrivateKey.generate()
    return (alice_pub, alice_priv, bob_pub, bob_priv, bob_prekey_priv)


def test_file_encrypt_decrypt_round_trip(tmp_path: Path, keypairs):
    alice_pub, alice_priv, bob_pub, bob_priv, bob_prekey_priv = keypairs
    alice_state = PeerState()
    bob_state = PeerState()

    file_bytes = b"hello encrypted file"
    file_path = tmp_path / "sample.bin"
    file_path.write_bytes(file_bytes)

    metadata_packet, chunk_iter, metadata = send_encrypted_file(
        alice_priv,
        bob_pub,
        alice_state,
        str(file_path),
        their_prekey_pub=bob_prekey_priv.public_key,
    )

    chunk_store: dict[tuple[bytes, int], bytes] = {}
    for idx, payload in chunk_iter:
        chunk_store[(metadata["file_id"], idx)] = payload

    def fetcher(file_id: bytes, chunk_index: int) -> bytes:
        return chunk_store.get((file_id, chunk_index))

    recovered = receive_encrypted_file(
        bob_priv,
        alice_pub,
        bob_state,
        metadata_packet,
        fetcher,
        my_prekey_priv=bob_prekey_priv,
    )
    assert recovered == file_bytes


def test_chunk_tamper_detection(tmp_path: Path, keypairs):
    alice_pub, alice_priv, bob_pub, bob_priv, bob_prekey_priv = keypairs
    alice_state = PeerState()
    file_bytes = b"integrity check"
    file_path = tmp_path / "tamper.bin"
    file_path.write_bytes(file_bytes)

    _, chunk_iter, metadata = send_encrypted_file(
        alice_priv,
        bob_pub,
        alice_state,
        str(file_path),
        their_prekey_pub=bob_prekey_priv.public_key,
    )

    encrypted_chunks = list(chunk_iter)
    # Corrupt first chunk
    idx, payload = encrypted_chunks[0]
    corrupted_payload = payload[:-1] + bytes([payload[-1] ^ 0xFF])
    encrypted_chunks[0] = (idx, corrupted_payload)

    with pytest.raises(Exception):
        decrypt_file_chunks(
            encrypted_chunks,
            metadata["file_key"],
            metadata["file_nonce_prefix"],
            metadata["file_hash"],
        )
