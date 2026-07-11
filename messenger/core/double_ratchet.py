"""Double Ratchet implementation using PyNaCl.

This module provides a simplified Signal-style Double Ratchet with X3DH-style
setup and message encryption using SecretBox. It avoids external dependencies
beyond PyNaCl and the standard library.
"""
from __future__ import annotations

import hashlib
import hmac
import copy
import struct
from dataclasses import dataclass, field
from typing import Dict, Iterable, Iterator, Optional, Tuple

from nacl.bindings import crypto_scalarmult
from nacl.public import PrivateKey, PublicKey
from nacl.secret import SecretBox
from nacl.signing import SigningKey, VerifyKey
from nacl.utils import random as nacl_random


HASH_LEN = hashlib.sha256().digest_size
PACKET_VERSION = 3
HEADER_FLAG_OBFUSCATED = 0x01
PLAIN_HEADER_LEN = 32 + 4  # dh public + message index
OBFUSCATED_HEADER_LEN = SecretBox.NONCE_SIZE + PLAIN_HEADER_LEN + SecretBox.MACBYTES
SIGNED_PREKEY_CONTEXT = b"p2p-chat-signed-prekey-v1"
PACKET_AUTH_CONTEXT = b"p2p-chat-packet-auth-v3"
PACKET_TAG_LEN = 32


def hkdf_sha256(input_key_material: bytes, salt: bytes = b"", info: bytes = b"", length: int = 32) -> bytes:
    """HKDF-SHA256 (RFC 5869) returning ``length`` bytes.

    Args:
        input_key_material: Initial keying material.
        salt: Optional salt (defaults to zeros if not provided).
        info: Optional context string.
        length: Output length in bytes.
    """
    if length <= 0:
        raise ValueError("length must be positive")
    if not salt:
        salt = b"\x00" * HASH_LEN
    prk = hmac.new(salt, input_key_material, hashlib.sha256).digest()
    t = b""
    okm = b""
    counter = 1
    while len(okm) < length:
        t = hmac.new(prk, t + info + bytes([counter]), hashlib.sha256).digest()
        okm += t
        counter += 1
    return okm[:length]


def hmac_sha256(key: bytes, data: bytes) -> bytes:
    """Return HMAC-SHA256(key, data)."""
    return hmac.new(key, data, hashlib.sha256).digest()


def dh(priv: PrivateKey, pub: PublicKey) -> bytes:
    """Perform X25519 Diffie–Hellman and guard against all-zero output."""
    shared = crypto_scalarmult(bytes(priv), bytes(pub))
    if shared == b"\x00" * 32:
        raise ValueError("invalid all-zero DH output")
    return shared


@dataclass
class IdentityKeyPair:
    """X25519 identity keypair and companion Ed25519 signing identity."""

    public: PublicKey
    private: PrivateKey
    signing: SigningKey

    @staticmethod
    def generate() -> "IdentityKeyPair":
        priv = PrivateKey.generate()
        signing = SigningKey.generate()
        return IdentityKeyPair(public=priv.public_key, private=priv, signing=signing)


@dataclass
class PreKeyBundle:
    """Represents a published pre-key bundle similar to Signal."""

    identity_pub: PublicKey
    identity_verify_pub: VerifyKey
    signed_prekey_pub: PublicKey
    signed_prekey_sig: bytes
    one_time_prekey_pub: Optional[PublicKey] = None

    def verify_signature(self) -> None:
        """Verify the signature on the signed pre-key using the verify key."""
        self.identity_verify_pub.verify(
            SIGNED_PREKEY_CONTEXT + bytes(self.signed_prekey_pub),
            self.signed_prekey_sig,
        )



@dataclass
class SessionState:
    """Holds Double Ratchet session state."""

    root_key: bytes
    send_chain_key: bytes
    recv_chain_key: bytes
    dh_send_key: PrivateKey
    dh_recv_key_pub: Optional[PublicKey]
    identity_local: IdentityKeyPair
    identity_remote: PublicKey
    send_idx: int = 0
    recv_idx: int = 0
    previous_recv_idx: int = 0
    skipped_message_keys: Dict[Tuple[bytes, int], bytes] = field(default_factory=dict)
    max_skip: int = 2000
    obfuscate_header: bool = False
    pending_send_ratchet: bool = False

    def ratchet_step(self, new_remote_dh_pub: PublicKey) -> None:
        """Perform the DH ratchet when a new remote public key is seen."""
        # Step 1: derive new root + recv chain
        dh_out = dh(self.dh_send_key, new_remote_dh_pub)
        rk_ck = hkdf_sha256(self.root_key + dh_out, salt=b"", info=b"DH-RATCHET", length=64)
        self.root_key, self.recv_chain_key = rk_ck[:32], rk_ck[32:64]

        # Step 2: advance our sending key
        self.dh_send_key = PrivateKey.generate()
        dh_out2 = dh(self.dh_send_key, new_remote_dh_pub)
        rk_ck2 = hkdf_sha256(self.root_key + dh_out2, salt=b"", info=b"DH-RATCHET", length=64)
        self.root_key, self.send_chain_key = rk_ck2[:32], rk_ck2[32:64]

        self.dh_recv_key_pub = new_remote_dh_pub
        self.previous_recv_idx = self.recv_idx
        self.recv_idx = 0
        self.send_idx = 0
        self.pending_send_ratchet = False

    def prime_send_ratchet(self) -> None:
        """Start a fresh sending chain once the peer's first ratchet key is known."""
        if not self.pending_send_ratchet:
            return
        if self.dh_recv_key_pub is None:
            raise ValueError("remote ratchet key missing")
        self.dh_send_key = PrivateKey.generate()
        dh_out = dh(self.dh_send_key, self.dh_recv_key_pub)
        rk_ck = hkdf_sha256(self.root_key + dh_out, salt=b"", info=b"DH-RATCHET", length=64)
        self.root_key, self.send_chain_key = rk_ck[:32], rk_ck[32:64]
        self.send_idx = 0
        self.pending_send_ratchet = False

    def derive_message_key(self, direction: str) -> bytes:
        """Derive and rotate a message key for send/recv direction."""
        if direction == "send":
            msg_key = hmac_sha256(self.send_chain_key, b"MsgKey")
            self.send_chain_key = hmac_sha256(self.send_chain_key, b"ChainKey")
            self.send_idx += 1
            return msg_key
        if direction == "recv":
            msg_key = hmac_sha256(self.recv_chain_key, b"MsgKey")
            self.recv_chain_key = hmac_sha256(self.recv_chain_key, b"ChainKey")
            self.recv_idx += 1
            return msg_key
        raise ValueError("direction must be 'send' or 'recv'")

    def store_skipped_key(self, dh_pub: PublicKey, index: int, key: bytes) -> None:
        """Store a skipped message key for potential out-of-order use."""
        if len(self.skipped_message_keys) >= self.max_skip:
            raise ValueError("too many skipped message keys")
        self.skipped_message_keys[(bytes(dh_pub), index)] = key

    def try_retrieve_skipped_key(self, dh_pub: PublicKey, index: int) -> Optional[bytes]:
        # Authentication must succeed before the one-shot key is consumed.
        return self.skipped_message_keys.get((bytes(dh_pub), index))


def _derive_three_keys(material: bytes) -> Tuple[bytes, bytes, bytes]:
    derived = hkdf_sha256(material, salt=b"", info=b"X3DH-INIT", length=96)
    return derived[:32], derived[32:64], derived[64:96]


def safety_number(
    local_identity_pub: PublicKey,
    remote_identity_pub: PublicKey,
    local_verify_pub: Optional[VerifyKey] = None,
    remote_verify_pub: Optional[VerifyKey] = None,
) -> str:
    """Return an order-independent, domain-separated safety number.

    Callers should supply the Ed25519 identities too.  The optional arguments
    retain source compatibility for stores created before signing identities
    were introduced.
    """
    identities = sorted((bytes(local_identity_pub), bytes(remote_identity_pub)))
    material = b"p2p-chat-safety-number-v2\x00" + b"".join(identities)
    if local_verify_pub is not None and remote_verify_pub is not None:
        material += b"\x01" + b"".join(sorted((bytes(local_verify_pub), bytes(remote_verify_pub))))
    digest = hashlib.sha256(material).digest()
    num = int.from_bytes(digest[:30], "big") % (10 ** 60)
    return f"{num:060d}"


def _sign_prekey(signing_key: SigningKey, prekey_pub: PublicKey) -> bytes:
    """Sign the pre-key using the companion Ed25519 signing key."""
    return signing_key.sign(SIGNED_PREKEY_CONTEXT + bytes(prekey_pub)).signature


def initialize_session_from_prekey(
    local_identity: IdentityKeyPair,
    remote_prekey: PreKeyBundle,
    local_ephemeral: IdentityKeyPair,
) -> SessionState:
    """Initialize a session as the initiator using the remote pre-key bundle."""
    remote_prekey.verify_signature()


    dh1 = dh(local_identity.private, remote_prekey.signed_prekey_pub)
    dh2 = dh(local_ephemeral.private, remote_prekey.identity_pub)
    dh3 = dh(local_ephemeral.private, remote_prekey.signed_prekey_pub)
    material = dh1 + dh2 + dh3
    if remote_prekey.one_time_prekey_pub is not None:
        dh4 = dh(local_ephemeral.private, remote_prekey.one_time_prekey_pub)
        material += dh4

    root_key, send_chain_key, recv_chain_key = _derive_three_keys(material)

    return SessionState(
        root_key=root_key,
        send_chain_key=send_chain_key,
        recv_chain_key=recv_chain_key,
        dh_send_key=local_ephemeral.private,
        dh_recv_key_pub=remote_prekey.signed_prekey_pub,
        identity_local=local_identity,
        identity_remote=remote_prekey.identity_pub,
    )


def respond_to_prekey_init(
    local_identity: IdentityKeyPair,
    signed_prekey: PrivateKey,
    local_one_time_prekey: Optional[PrivateKey],
    initiator_identity_pub: PublicKey,
    initiator_ephemeral_pub: PublicKey,
) -> SessionState:
    """Responder creates a session after receiving an initial request."""
    dh1 = dh(signed_prekey, initiator_identity_pub)
    dh2 = dh(local_identity.private, initiator_ephemeral_pub)
    dh3 = dh(signed_prekey, initiator_ephemeral_pub)
    material = dh1 + dh2 + dh3
    if local_one_time_prekey is not None:
        dh4 = dh(local_one_time_prekey, initiator_ephemeral_pub)
        material += dh4

    root_key, recv_chain_key, send_chain_key = _derive_three_keys(material)

    return SessionState(
        root_key=root_key,
        send_chain_key=send_chain_key,
        recv_chain_key=recv_chain_key,
        dh_send_key=signed_prekey,
        dh_recv_key_pub=initiator_ephemeral_pub,
        identity_local=local_identity,
        identity_remote=initiator_identity_pub,
        pending_send_ratchet=True,
    )


def encrypt_message(session: SessionState, plaintext: bytes) -> bytes:
    """Encrypt a message with the current send ratchet state."""
    session.prime_send_ratchet()
    msg_index = session.send_idx
    message_key = session.derive_message_key("send")
    nonce = nacl_random(SecretBox.NONCE_SIZE)
    box = SecretBox(message_key)
    ciphertext = box.encrypt(plaintext, nonce)

    header_plain = b"".join(
        [bytes(session.dh_send_key.public_key), struct.pack(">I", msg_index)]
    )

    flags = 0
    if session.obfuscate_header:
        flags |= HEADER_FLAG_OBFUSCATED
        header_key = hmac_sha256(session.root_key, b"HeaderKey")
        header_box = SecretBox(header_key)
        header = header_box.encrypt(header_plain, nacl_random(SecretBox.NONCE_SIZE))
    else:
        header = header_plain

    prefix = b"".join([bytes([PACKET_VERSION]), bytes([flags]), header, ciphertext])
    auth_key = hmac_sha256(message_key, PACKET_AUTH_CONTEXT)
    packet = prefix + hmac_sha256(auth_key, prefix)
    return packet


def _maybe_skip_message_keys(session: SessionState, until: int, dh_pub: PublicKey) -> None:
    if until - session.recv_idx > session.max_skip:
        raise ValueError("too many skipped messages")
    while session.recv_idx < until:
        key = session.derive_message_key("recv")
        session.store_skipped_key(dh_pub, session.recv_idx - 1, key)


def decrypt_message(session: SessionState, packet: bytes) -> bytes:
    """Decrypt a packet and advance the receive ratchet as needed."""
    # Work on a private snapshot. Malformed or forged packets must not advance
    # chains, install a hostile ratchet key, or consume skipped keys.
    candidate = copy.copy(session)
    candidate.skipped_message_keys = session.skipped_message_keys.copy()
    plaintext = _decrypt_message_candidate(candidate, packet)
    for name in SessionState.__dataclass_fields__:
        setattr(session, name, getattr(candidate, name))
    return plaintext


def _decrypt_message_candidate(session: SessionState, packet: bytes) -> bytes:
    if len(packet) < 1 + 1 + PLAIN_HEADER_LEN + PACKET_TAG_LEN:
        raise ValueError("packet too short")
    version = packet[0]
    if version != PACKET_VERSION:
        raise ValueError("unsupported version")

    flags = packet[1]
    offset = 2

    if flags & HEADER_FLAG_OBFUSCATED:
        header_end = offset + OBFUSCATED_HEADER_LEN
        if len(packet) < header_end:
            raise ValueError("packet too short")
        header_key = hmac_sha256(session.root_key, b"HeaderKey")
        header_box = SecretBox(header_key)
        header_plain = header_box.decrypt(packet[offset:header_end])
    else:
        header_end = offset + PLAIN_HEADER_LEN
        header_plain = packet[offset:header_end]

    remote_dh_pub = PublicKey(header_plain[:32])
    msg_index = struct.unpack(">I", header_plain[32:36])[0]
    ciphertext = packet[header_end:-PACKET_TAG_LEN]
    supplied_tag = packet[-PACKET_TAG_LEN:]

    skipped_key = session.try_retrieve_skipped_key(remote_dh_pub, msg_index)
    if skipped_key is not None:
        auth_key = hmac_sha256(skipped_key, PACKET_AUTH_CONTEXT)
        if not hmac.compare_digest(supplied_tag, hmac_sha256(auth_key, packet[:-PACKET_TAG_LEN])):
            raise ValueError("packet authentication failed")
        box = SecretBox(skipped_key)
        plaintext = box.decrypt(ciphertext)
        session.skipped_message_keys.pop((bytes(remote_dh_pub), msg_index), None)
        return plaintext

    if session.dh_recv_key_pub is None or bytes(remote_dh_pub) != bytes(session.dh_recv_key_pub):
        session.ratchet_step(remote_dh_pub)

    if msg_index < session.recv_idx:
        raise ValueError("duplicate or old message")

    _maybe_skip_message_keys(session, msg_index, remote_dh_pub)
    message_key = session.derive_message_key("recv")
    auth_key = hmac_sha256(message_key, PACKET_AUTH_CONTEXT)
    if not hmac.compare_digest(supplied_tag, hmac_sha256(auth_key, packet[:-PACKET_TAG_LEN])):
        raise ValueError("packet authentication failed")
    box = SecretBox(message_key)
    return box.decrypt(ciphertext)


# Demo scenario
if __name__ == "__main__":
    print("--- Double Ratchet demo ---")
    alice_id = IdentityKeyPair.generate()
    bob_id = IdentityKeyPair.generate()

    # Bob publishes pre-key bundle
    bob_signed_prekey = PrivateKey.generate()
    bob_one_time_prekey = PrivateKey.generate()
    signature = _sign_prekey(bob_id.signing, bob_signed_prekey.public_key)
    bob_bundle = PreKeyBundle(
        identity_pub=bob_id.public,
        identity_verify_pub=bob_id.signing.verify_key,
        signed_prekey_pub=bob_signed_prekey.public_key,
        signed_prekey_sig=signature,
        one_time_prekey_pub=bob_one_time_prekey.public_key,
    )

    # Alice initializes using Bob's bundle
    alice_ephemeral = IdentityKeyPair.generate()
    alice_session = initialize_session_from_prekey(alice_id, bob_bundle, alice_ephemeral)


    # Bob responds to Alice
    bob_session = respond_to_prekey_init(
        local_identity=bob_id,
        signed_prekey=bob_signed_prekey,
        local_one_time_prekey=bob_one_time_prekey,
        initiator_identity_pub=alice_id.public,
        initiator_ephemeral_pub=alice_ephemeral.public,
    )

    print("Safety number (Alice/Bob):", safety_number(alice_id.public, bob_id.public))

    # Alice sends messages
    packets = []
    for text in [b"Hello Bob", b"How are you?", b"Here's msg3"]:
        packets.append(encrypt_message(alice_session, text))

    # Bob receives
    for pkt in packets:
        plain = decrypt_message(bob_session, pkt)
        print("Bob got:", plain)

    # Bob replies out-of-order
    bob_packets = [encrypt_message(bob_session, b"Hi Alice"), encrypt_message(bob_session, b"All good!")]
    # Deliver second first to test skipped key handling
    alice_plain2 = decrypt_message(alice_session, bob_packets[1])
    alice_plain1 = decrypt_message(alice_session, bob_packets[0])
    print("Alice got out-of-order:", alice_plain2, alice_plain1)

    print("Demo complete")
