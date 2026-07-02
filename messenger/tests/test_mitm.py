import asyncio

import pytest
from nacl.public import PrivateKey

from messenger.core.crypto import (
    PeerState,
    decrypt_message,
    encrypt_message,
)
from messenger.core.identity import (
    TrustStore,
    fingerprint,
    load_or_create_identity,
    load_or_create_signing_identity,
)
from messenger.core.session import Session


@pytest.mark.asyncio
async def test_mitm_rejected_and_cannot_decrypt(monkeypatch, tmp_path):
    monkeypatch.setenv("P2PCHAT_CONFIG_DIR", str(tmp_path))

    alice_priv = load_or_create_identity(str(tmp_path / "alice.key"))
    bob_priv = load_or_create_identity(str(tmp_path / "bob.key"))
    eve_priv = load_or_create_identity(str(tmp_path / "eve.key"))

    alice_sign = load_or_create_signing_identity(str(tmp_path / "alice.sign"))
    bob_sign = load_or_create_signing_identity(str(tmp_path / "bob.sign"))
    eve_sign = load_or_create_signing_identity(str(tmp_path / "eve.sign"))

    bob_fp = fingerprint(bob_sign.verify_key)

    async def eve_server(reader, writer):
        # Eve tries to impersonate Bob; Alice should reject when expecting Bob.
        try:
            await Session.create(
                reader,
                writer,
                initiator=False,
                identity_priv=eve_priv,
                signing_key=eve_sign,
                trust_store=TrustStore(),
            )
        except Exception:
            pass
        finally:
            writer.close()
            await writer.wait_closed()

    server = await asyncio.start_server(eve_server, "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]

    reader, writer = await asyncio.open_connection("127.0.0.1", port)
    with pytest.raises(ValueError):
        await Session.create(
            reader,
            writer,
            initiator=True,
            identity_priv=alice_priv,
            signing_key=alice_sign,
            trust_store=TrustStore(),
            expected_fingerprint=bob_fp,
        )
    writer.close()
    await writer.wait_closed()

    server.close()
    await server.wait_closed()

    # Confirm Eve cannot decrypt ciphertext destined for Bob.
    state_alice = PeerState()
    state_bob = PeerState()
    bob_prekey_priv = PrivateKey.generate()
    bob_prekey_pub = bob_prekey_priv.public_key
    ciphertext = encrypt_message(
        alice_priv,
        bob_priv.public_key,
        state_alice,
        b"attack-resilient",
        their_prekey_pub=bob_prekey_pub,
    )
    assert (
        decrypt_message(
            bob_priv,
            alice_priv.public_key,
            state_bob,
            ciphertext,
            my_prekey_priv=bob_prekey_priv,
        )
        == b"attack-resilient"
    )

    with pytest.raises(Exception):
        decrypt_message(
            eve_priv,
            alice_priv.public_key,
            PeerState(),
            ciphertext,
        )

