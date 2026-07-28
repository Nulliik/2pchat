from __future__ import annotations

"""Meshtastic-inspired end-to-end encryption helpers.

This module implements identity-based X25519 key exchange with ephemeral keys,
HKDF-SHA256 key derivation, SecretBox encryption, and replay protection via
message counters. It follows the design requested in the prompt and is intended
as a self-contained, PyNaCl-only dependency aside from the Python stdlib.
"""

import base64
import json
import logging
import os
import tempfile
from hashlib import scrypt, sha256
import hmac
from pathlib import Path
from typing import Callable, Iterable, Iterator, Tuple

try:
    from argon2.low_level import Type as Argon2Type
    from argon2.low_level import hash_secret_raw

    ARGON2_AVAILABLE = True
except ImportError:  # pragma: no cover - exercised when argon2-cffi is absent
    ARGON2_AVAILABLE = False

from nacl.bindings import crypto_scalarmult
from nacl.public import PrivateKey, PublicKey
from nacl.secret import SecretBox
from nacl.utils import random as nacl_random

from messenger.utils.logger import setup_logger
from .protocol import DEFAULT_FILE_CHUNK_SIZE


HKDF_HASH_LEN = sha256().digest_size
MESSAGE_PACKET_VERSION = 2
MESSAGE_PACKET_TAG_SIZE = sha256().digest_size
MESSAGE_PACKET_AUTH_CONTEXT = b"2pchat-message-packet-v2"

logger = setup_logger("messenger.crypto", logging.INFO)

KDF_ARGON2ID = "argon2id"
KDF_SCRYPT = "scrypt"

_ARGON2_DEFAULT_PARAMS = {
    "time_cost": 3,
    "memory_cost": 64 * 1024,  # kibibytes (64 MiB)
    "parallelism": 1,
    "hash_len": 32,
}

_SCRYPT_DEFAULT_PARAMS = {
    "n": 2**15,
    "r": 8,
    "p": 1,
    "dklen": 32,
    "maxmem": 64 * 1024 * 1024,
}


def wipe_buffer(buf: bytearray) -> None:
    """Overwrites a mutable bytearray with zeroes to remove secrets from memory."""
    if isinstance(buf, bytearray):
        for i in range(len(buf)):
            buf[i] = 0


def generate_identity_keypair() -> Tuple[PublicKey, PrivateKey]:
    """Generate a new X25519 identity keypair."""

    priv = PrivateKey.generate()
    return priv.public_key, priv


def _validate_salt(salt: bytes) -> bytes:
    if not isinstance(salt, (bytes, bytearray)):
        raise TypeError("salt must be bytes")
    if len(salt) < 16:
        raise ValueError("salt must be at least 16 bytes")
    return bytes(salt)


def _derive_argon2id(password: str, salt: bytes, params: dict | None = None) -> tuple[bytes, dict]:
    if not ARGON2_AVAILABLE:
        raise RuntimeError("argon2id requested but argon2-cffi is not installed")

    cfg = {**_ARGON2_DEFAULT_PARAMS, **(params or {})}
    derived = hash_secret_raw(
        secret=password.encode("utf-8"),
        salt=salt,
        time_cost=cfg["time_cost"],
        memory_cost=cfg["memory_cost"],
        parallelism=cfg["parallelism"],
        hash_len=cfg["hash_len"],
        type=Argon2Type.ID,
    )
    metadata = {"kdf": KDF_ARGON2ID, "params": cfg, "salt": base64.b64encode(salt).decode()}
    return derived, metadata


def _derive_scrypt(password: str, salt: bytes, params: dict | None = None) -> tuple[bytes, dict]:
    cfg = {**_SCRYPT_DEFAULT_PARAMS, **(params or {})}
    derived = scrypt(
        password=password.encode("utf-8"),
        salt=salt,
        n=cfg["n"],
        r=cfg["r"],
        p=cfg["p"],
        maxmem=cfg["maxmem"],
        dklen=cfg["dklen"],
    )
    metadata = {"kdf": KDF_SCRYPT, "params": cfg, "salt": base64.b64encode(salt).decode()}
    return derived, metadata


def derive_channel_key(
    password: str,
    salt: bytes,
    *,
    kdf: str = KDF_ARGON2ID,
) -> tuple[bytes, dict]:
    """Derive a 32-byte channel key using a versioned, memory-hard KDF.

    Argon2id is preferred; scrypt is used as a fallback when argon2-cffi is
    unavailable. Legacy PBKDF2 has been removed to avoid weak, GPU-friendly
    derivations.
    """

    salt = _validate_salt(salt)

    if kdf == KDF_ARGON2ID:
        if not ARGON2_AVAILABLE:
            logger.warning(
                "Argon2id unavailable; falling back to scrypt for channel key derivation"
            )
            return _derive_scrypt(password, salt)
        return _derive_argon2id(password, salt)

    if kdf == KDF_SCRYPT:
        return _derive_scrypt(password, salt)

    raise ValueError(f"unsupported kdf {kdf}")


def derive_channel_key_from_password(password: str, salt: bytes) -> bytes:
    """Compatibility wrapper deriving a channel key with the default KDF."""

    derived, _ = derive_channel_key(password, salt)
    return derived


def derive_channel_key_from_metadata(password: str, metadata: dict) -> bytes:
    """Re-derive a channel key using stored KDF metadata.

    This reconstructs Argon2id/scrypt keys with their original parameters.
    """

    if not isinstance(metadata, dict):
        raise TypeError("metadata must be a dict")

    kdf_name = metadata.get("kdf")
    salt_b64 = metadata.get("salt")
    params = metadata.get("params", {}) or {}
    if not kdf_name or not salt_b64:
        raise ValueError("metadata missing kdf or salt")

    try:
        salt = base64.b64decode(salt_b64, validate=True)
    except Exception as exc:  # pragma: no cover - defensive
        raise ValueError("invalid salt encoding") from exc

    salt = _validate_salt(salt)

    if kdf_name == KDF_ARGON2ID:
        if not ARGON2_AVAILABLE:
            raise RuntimeError("argon2id metadata encountered but argon2-cffi is not installed")
        derived, _ = _derive_argon2id(password, salt, params=params)
        return derived

    if kdf_name == KDF_SCRYPT:
        derived, _ = _derive_scrypt(password, salt, params=params)
        return derived

    raise ValueError(f"unsupported kdf {kdf_name}")


def rotate_channel_password(
    old_password: str,
    new_password: str,
    old_metadata: dict,
) -> tuple[bytes, dict]:
    """Rotate a channel password to Argon2id while verifying the old secret.

    The previous password is re-derived using ``old_metadata`` to ensure callers
    supply the correct secret. The new key always uses Argon2id (falling back to
    scrypt only if Argon2 is unavailable) with a fresh random salt.
    """

    # Ensure the old password is correct or fail before rotating
    _ = derive_channel_key_from_metadata(old_password, old_metadata)

    new_salt = nacl_random(16)
    new_key, new_meta = derive_channel_key(new_password, new_salt, kdf=KDF_ARGON2ID)
    return new_key, new_meta


def hkdf_sha256(input_key_material: bytes, salt: bytes = b"", info: bytes = b"", length: int = 32) -> bytes:
    """HKDF implementation (RFC 5869) using SHA-256."""

    if length < 0 or length > 255 * HKDF_HASH_LEN:
        raise ValueError("invalid HKDF output length")

    hkdf_salt = salt or b"\x00" * HKDF_HASH_LEN
    prk = hmac.new(hkdf_salt, input_key_material, sha256).digest()
    output = bytearray()
    previous = b""
    for counter in range(1, (length + HKDF_HASH_LEN - 1) // HKDF_HASH_LEN + 1):
        previous = hmac.new(prk, previous + info + bytes([counter]), sha256).digest()
        output.extend(previous)
    return bytes(output[:length])


def _assert_nonzero(shared: bytes) -> None:
    if shared == b"\x00" * 32:
        raise ValueError("all-zero shared key detected")


def derive_session_key(
    my_identity_priv: PrivateKey,
    their_identity_pub: PublicKey,
    my_ephemeral_priv: PrivateKey,
    *,
    their_prekey_pub: PublicKey,
    channel_key: bytes | None = None,
) -> bytes:
    """Derive a 32-byte session key using dual/triple ECDH and HKDF.

    The derivation always binds to both the peer identity and peer prekey:

    - shared1 = DH(my_ephemeral_priv, their_identity_pub)
    - shared2 = DH(my_ephemeral_priv, their_prekey_pub)
    - shared3 = DH(my_identity_priv, their_prekey_pub)
    """

    shared1 = crypto_scalarmult(bytes(my_ephemeral_priv), bytes(their_identity_pub))
    _assert_nonzero(shared1)

    shared2 = crypto_scalarmult(bytes(my_ephemeral_priv), bytes(their_prekey_pub))
    _assert_nonzero(shared2)

    shared3 = crypto_scalarmult(bytes(my_identity_priv), bytes(their_prekey_pub))
    _assert_nonzero(shared3)

    ikm = shared1 + shared2 + shared3
    salt = channel_key if channel_key is not None else b""
    info = b"MeshtasticStyleSessionKey"
    return hkdf_sha256(ikm, salt=salt, info=info, length=32)



class PeerState:
    """Maintain per-peer counters for replay protection."""

    REPLAY_WINDOW_SIZE = 64

    def __init__(self) -> None:
        self.send_counter: int = 0
        self.recv_highest_counter: int = -1
        self.recv_counter_bitmap: int = 0

    def next_send_counter(self) -> int:
        self.send_counter += 1
        return self.send_counter

    def validate_received_counter(self, counter: int) -> None:
        if counter < 0:
            raise ValueError("invalid message counter")
        if counter > self.recv_highest_counter:
            return
        distance = self.recv_highest_counter - counter
        if distance >= self.REPLAY_WINDOW_SIZE:
            raise ValueError("message counter is outside replay window")
        if self.recv_counter_bitmap & (1 << distance):
            raise ValueError("replay detected")

    def record_received_counter(self, counter: int) -> None:
        self.validate_received_counter(counter)
        if counter > self.recv_highest_counter:
            shift = counter - self.recv_highest_counter
            if shift >= self.REPLAY_WINDOW_SIZE:
                self.recv_counter_bitmap = 1
            else:
                mask = (1 << self.REPLAY_WINDOW_SIZE) - 1
                self.recv_counter_bitmap = ((self.recv_counter_bitmap << shift) | 1) & mask
            self.recv_highest_counter = counter
            return
        distance = self.recv_highest_counter - counter
        self.recv_counter_bitmap |= 1 << distance


# --- File encryption helpers (Telegram-style per-file key/nonce) ---


def encrypt_file_in_chunks(
    file_path: str,
    chunk_size: int = DEFAULT_FILE_CHUNK_SIZE,
) -> Tuple[Iterator[Tuple[int, bytes]], bytes, bytes, int, int, bytes]:
    """Encrypt a file in chunks using a fresh symmetric key.

    Returns an iterator of ``(chunk_index, encrypted_chunk)`` pairs along with
    ``(file_key, file_nonce_prefix, file_size, num_chunks, file_hash)``.
    The file is scanned once for metadata and then encrypted lazily. Memory use
    is therefore bounded by ``chunk_size`` regardless of the file size.
    """

    path = Path(file_path)
    file_size = path.stat().st_size
    digest = sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    file_hash = digest.digest()

    file_key = nacl_random(SecretBox.KEY_SIZE)
    file_nonce_prefix = nacl_random(16)
    box = SecretBox(file_key)

    if chunk_size <= 0:
        raise ValueError("chunk_size must be positive")
    num_chunks = (file_size + chunk_size - 1) // chunk_size

    def iterator() -> Iterator[Tuple[int, bytes]]:
        with path.open("rb") as source:
            for chunk_index in range(num_chunks):
                chunk = source.read(chunk_size)
                nonce = file_nonce_prefix + chunk_index.to_bytes(8, "big")
                yield chunk_index, bytes(box.encrypt(chunk, nonce))

    logger.debug(
        "Encrypt file %s size=%s chunk_size=%s chunks=%s",
        file_path,
        file_size,
        chunk_size,
        num_chunks,
    )

    return iterator(), file_key, file_nonce_prefix, file_size, num_chunks, file_hash


def decrypt_file_chunks(
    chunks: Iterable[Tuple[int, bytes]],
    file_key: bytes,
    file_nonce_prefix: bytes,
    expected_sha256: bytes,
) -> bytes:
    """Decrypt chunks produced by :func:`encrypt_file_in_chunks`.

    Raises ``ValueError`` on missing chunks or hash mismatch, and propagates
    ``CryptoError`` on authentication failures.
    """

    box = SecretBox(file_key)
    by_index: dict[int, bytes] = {}
    for chunk_index, encrypted_chunk in chunks:
        # ``encrypted_chunk`` already includes the nonce prefix added at
        # encryption time, so we do not pass an explicit nonce here to avoid
        # double-supplying it.
        plaintext = box.decrypt(encrypted_chunk)
        by_index[chunk_index] = plaintext

    if not by_index:
        return b""

    max_index = max(by_index)
    ordered: list[bytes] = []
    for idx in range(max_index + 1):
        if idx not in by_index:
            raise ValueError("missing chunk %s" % idx)
        ordered.append(by_index[idx])

    assembled = b"".join(ordered)
    digest = sha256(assembled).digest()
    if digest != expected_sha256:
        raise ValueError("file hash mismatch")
    logger.debug(
        "Decrypt file size=%s expected_hash=%s",
        len(assembled),
        expected_sha256.hex(),
    )
    return assembled


def _encode_metadata(
    file_id: bytes,
    file_key: bytes,
    file_nonce_prefix: bytes,
    file_size: int,
    num_chunks: int,
    file_hash: bytes,
    file_name: str | None,
) -> bytes:
    payload = {
        "file_id": base64.b64encode(file_id).decode(),
        "file_key": base64.b64encode(file_key).decode(),
        "file_nonce_prefix": base64.b64encode(file_nonce_prefix).decode(),
        "file_size": file_size,
        "num_chunks": num_chunks,
        "file_hash": base64.b64encode(file_hash).decode(),
        "file_name": file_name,
    }
    return json.dumps(payload, separators=(",", ":")).encode("utf-8")


def _decode_metadata(blob: bytes) -> dict:
    data = json.loads(blob.decode("utf-8"))
    required = [
        "file_id",
        "file_key",
        "file_nonce_prefix",
        "file_size",
        "num_chunks",
        "file_hash",
    ]
    for key in required:
        if key not in data:
            raise ValueError(f"missing metadata field {key}")

    return {
        "file_id": base64.b64decode(data["file_id"]),
        "file_key": base64.b64decode(data["file_key"]),
        "file_nonce_prefix": base64.b64decode(data["file_nonce_prefix"]),
        "file_size": int(data["file_size"]),
        "num_chunks": int(data["num_chunks"]),
        "file_hash": base64.b64decode(data["file_hash"]),
        "file_name": data.get("file_name"),
    }


def send_encrypted_file(
    my_identity_priv: PrivateKey,
    their_identity_pub: PublicKey,
    peer_state: PeerState,
    file_path: str,
    *,
    their_prekey_pub: PublicKey,
    channel_key: bytes | None = None,
    chunk_size: int = DEFAULT_FILE_CHUNK_SIZE,
    file_name: str | None = None,
) -> Tuple[bytes, Iterator[Tuple[int, bytes]], dict]:
    """Prepare encrypted file transfer metadata and chunk iterator.

    Returns a tuple of ``(metadata_packet, chunk_iterator, metadata_dict)`` so
    the caller can send the metadata first and stream chunks through their
    transport. The metadata packet is encrypted with :func:`encrypt_message` and
    uses the same identity/channel keys as normal chat messages.
    """

    (
        chunk_iterator,
        file_key,
        file_nonce_prefix,
        file_size,
        num_chunks,
        file_hash,
    ) = encrypt_file_in_chunks(file_path, chunk_size=chunk_size)

    file_id = os.urandom(12)
    metadata_bytes = _encode_metadata(
        file_id,
        file_key,
        file_nonce_prefix,
        file_size,
        num_chunks,
        file_hash,
        file_name=file_name,
    )
    metadata_packet = encrypt_message(
        my_identity_priv,
        their_identity_pub,
        peer_state,
        metadata_bytes,
        channel_key=channel_key,
        their_prekey_pub=their_prekey_pub,
    )

    metadata = {
        "file_id": file_id,
        "file_key": file_key,
        "file_nonce_prefix": file_nonce_prefix,
        "file_size": file_size,
        "num_chunks": num_chunks,
        "file_hash": file_hash,
        "file_name": file_name,
    }

    return metadata_packet, chunk_iterator, metadata


def receive_encrypted_file(
    my_identity_priv: PrivateKey,
    their_identity_pub: PublicKey,
    peer_state: PeerState,
    metadata_packet: bytes,
    chunk_fetcher: Callable[[bytes, int], bytes | None],
    *,
    my_prekey_priv: PrivateKey,
    channel_key: bytes | None = None,
) -> bytes:
    """Decrypt metadata, download encrypted chunks, and return plaintext bytes.

    ``chunk_fetcher`` is a callable ``(file_id: bytes, chunk_index: int) -> bytes``
    that retrieves the encrypted chunk payload for a given index.
    """

    metadata_bytes = decrypt_message(
        my_identity_priv,
        their_identity_pub,
        peer_state,
        metadata_packet,
        channel_key=channel_key,
        my_prekey_priv=my_prekey_priv,
    )
    metadata = _decode_metadata(metadata_bytes)

    file_id = metadata["file_id"]
    file_key = metadata["file_key"]
    file_nonce_prefix = metadata["file_nonce_prefix"]
    num_chunks = metadata["num_chunks"]
    file_hash = metadata["file_hash"]

    encrypted_chunks: list[Tuple[int, bytes]] = []
    for idx in range(num_chunks):
        chunk_payload = chunk_fetcher(file_id, idx)
        if chunk_payload is None:
            raise ValueError(f"missing chunk {idx}")
        encrypted_chunks.append((idx, chunk_payload))

    return decrypt_file_chunks(
        encrypted_chunks,
        file_key=file_key,
        file_nonce_prefix=file_nonce_prefix,
        expected_sha256=file_hash,
    )


def encrypt_message(
    my_identity_priv: PrivateKey,
    their_identity_pub: PublicKey,
    peer_state: PeerState,
    plaintext: bytes,
    channel_key: bytes | None = None,
    *,
    their_prekey_pub: PublicKey,
) -> bytes:
    """Encrypt a plaintext for a peer using ephemeral keys and counters."""

    ephemeral_priv = PrivateKey.generate()
    ephemeral_pub = ephemeral_priv.public_key
    session_key = derive_session_key(
        my_identity_priv,
        their_identity_pub,
        ephemeral_priv,
        their_prekey_pub=their_prekey_pub,
        channel_key=channel_key,
    )

    box = SecretBox(session_key)
    nonce = nacl_random(SecretBox.NONCE_SIZE)
    ciphertext_bytes = box.encrypt(plaintext, nonce)

    counter = peer_state.next_send_counter()
    prefix = b"".join(
        [
            bytes([MESSAGE_PACKET_VERSION]),
            counter.to_bytes(8, "big"),
            bytes(ephemeral_pub),
            ciphertext_bytes,
        ]
    )
    auth_key = hmac.new(session_key, MESSAGE_PACKET_AUTH_CONTEXT, sha256).digest()
    packet = prefix + hmac.new(auth_key, prefix, sha256).digest()
    logger.debug(
        "Encrypt: counter=%s plaintext=%sB cipher=%sB packet=%sB",
        counter,
        len(plaintext),
        len(ciphertext_bytes),
        len(packet),
    )
    return packet


def _derive_session_key_for_decrypt(
    my_identity_priv: PrivateKey,
    their_identity_pub: PublicKey,
    their_ephemeral_pub: PublicKey,
    channel_key: bytes | None,
    *,
    my_prekey_priv: PrivateKey,
) -> bytes:
    shared1 = crypto_scalarmult(bytes(my_identity_priv), bytes(their_ephemeral_pub))
    shared2 = crypto_scalarmult(bytes(my_prekey_priv), bytes(their_ephemeral_pub))
    shared3 = crypto_scalarmult(bytes(my_prekey_priv), bytes(their_identity_pub))

    _assert_nonzero(shared1)
    _assert_nonzero(shared2)
    _assert_nonzero(shared3)

    ikm = shared1 + shared2 + shared3
    salt = channel_key if channel_key is not None else b""
    info = b"MeshtasticStyleSessionKey"
    return hkdf_sha256(ikm, salt=salt, info=info, length=32)



def decrypt_message(
    my_identity_priv: PrivateKey,
    their_identity_pub: PublicKey,
    peer_state: PeerState,
    packet: bytes,
    channel_key: bytes | None = None,
    *,
    my_prekey_priv: PrivateKey,
) -> bytes:
    """Decrypt a packet produced by :func:`encrypt_message`."""

    min_len = 1 + 8 + 32 + SecretBox.NONCE_SIZE + SecretBox.MACBYTES + MESSAGE_PACKET_TAG_SIZE
    if len(packet) < min_len:
        raise ValueError("packet too short")

    version = packet[0]
    if version != MESSAGE_PACKET_VERSION:
        raise ValueError("unsupported version")

    counter = int.from_bytes(packet[1:9], "big")
    ephemeral_pub_bytes = packet[9:41]
    ciphertext_bytes = packet[41:-MESSAGE_PACKET_TAG_SIZE]
    supplied_tag = packet[-MESSAGE_PACKET_TAG_SIZE:]

    if not ciphertext_bytes:
        raise ValueError("ciphertext missing")

    # Reject known replays before doing expensive public-key work, but only
    # mutate the window after authentication succeeds. A forged high counter
    # must not be able to lock out later valid messages.
    peer_state.validate_received_counter(counter)
    ephemeral_pub = PublicKey(ephemeral_pub_bytes)

    session_key = _derive_session_key_for_decrypt(
        my_identity_priv,
        their_identity_pub,
        ephemeral_pub,
        channel_key,
        my_prekey_priv=my_prekey_priv,
    )
    auth_key = hmac.new(session_key, MESSAGE_PACKET_AUTH_CONTEXT, sha256).digest()
    expected_tag = hmac.new(auth_key, packet[:-MESSAGE_PACKET_TAG_SIZE], sha256).digest()
    if not hmac.compare_digest(supplied_tag, expected_tag):
        raise ValueError("packet authentication failed")
    box = SecretBox(session_key)
    plaintext = box.decrypt(ciphertext_bytes)
    peer_state.record_received_counter(counter)
    logger.debug(
        "Decrypt: counter=%s cipher=%sB packet=%sB plaintext=%sB",
        counter,
        len(ciphertext_bytes),
        len(packet),
        len(plaintext),
    )
    return plaintext


if __name__ == "__main__":
    alice_pub, alice_priv = generate_identity_keypair()
    bob_pub, bob_priv = generate_identity_keypair()

    alice_state = PeerState()
    bob_state = PeerState()

    salt = nacl_random(16)
    channel_key = derive_channel_key_from_password("test-channel", salt)

    bob_prekey = PrivateKey.generate()

    packet = encrypt_message(
        alice_priv,
        bob_pub,
        alice_state,
        b"hello Bob",
        channel_key,
        their_prekey_pub=bob_prekey.public_key,
    )
    print("packet length", len(packet))

    plaintext = decrypt_message(
        bob_priv,
        alice_pub,
        bob_state,
        packet,
        channel_key,
        my_prekey_priv=bob_prekey,
    )
    print("decrypted", plaintext)

    try:
        decrypt_message(
            bob_priv,
            alice_pub,
            bob_state,
            packet,
            channel_key,
            my_prekey_priv=bob_prekey,
        )
    except ValueError as exc:  # expected replay
        print("replay detected:", exc)

    # File-transfer demo with in-memory chunk storage
    with tempfile.NamedTemporaryFile(delete=False) as tmp:
        tmp.write(b"example-file-bytes")
        file_path = tmp.name

    metadata_packet, chunk_iter, metadata = send_encrypted_file(
        alice_priv,
        bob_pub,
        alice_state,
        file_path,
        their_prekey_pub=bob_prekey.public_key,
        channel_key=channel_key,
        file_name="demo.bin",
    )

    storage: dict[tuple[bytes, int], bytes] = {}
    for idx, payload in chunk_iter:
        storage[(metadata["file_id"], idx)] = payload

    def fetch_chunk(file_id: bytes, idx: int) -> bytes:
        return storage.get((file_id, idx))

    reconstructed = receive_encrypted_file(
        bob_priv,
        alice_pub,
        bob_state,
        metadata_packet,
        fetch_chunk,
        my_prekey_priv=bob_prekey,
        channel_key=channel_key,
    )
    print("file round trip ok", reconstructed == b"example-file-bytes")

    # Tamper with a chunk to demonstrate integrity failure
    corrupt_storage = dict(storage)
    first_key = next(iter(corrupt_storage))
    corrupted_payload = corrupt_storage[first_key]
    corrupt_storage[first_key] = corrupted_payload[:-1] + bytes([corrupted_payload[-1] ^ 0xFF])

    def fetch_corrupt(file_id: bytes, idx: int) -> bytes:
        return corrupt_storage.get((file_id, idx))

    try:
        receive_encrypted_file(
            bob_priv,
            alice_pub,
            bob_state,
            metadata_packet,
            fetch_corrupt,
            my_prekey_priv=bob_prekey,
            channel_key=channel_key,
        )
    except Exception as exc:  # noqa: BLE001 - demo logging
        print("tampering detected", exc)
