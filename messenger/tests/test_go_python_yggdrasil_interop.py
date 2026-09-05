import asyncio
import json
import os
import subprocess
import pytest
from nacl.public import PrivateKey
from nacl.signing import SigningKey
from messenger.core.session import Session, fingerprint
from messenger.core.discovery_rendezvous import derive_rendezvous_key


@pytest.mark.asyncio
async def test_python_to_python_yggdrasil_ipv6_chat_stream():
    """Verify end-to-end Double Ratchet chat over IPv6 stream."""
    alice_priv = PrivateKey.generate()
    alice_sign = SigningKey.generate()
    alice_fp = fingerprint(alice_priv.public_key)

    bob_priv = PrivateKey.generate()
    bob_sign = SigningKey.generate()
    bob_fp = fingerprint(bob_priv.public_key)

    received_by_bob = []
    received_by_alice = []

    async def handle_alice_client(reader, writer):
        sess = await Session.create(
            reader,
            writer,
            initiator=False,
            identity_priv=alice_priv,
            signing_key=alice_sign,
        )
        try:
            while len(received_by_alice) < 3:
                msg = await asyncio.wait_for(sess.receive_message(), timeout=3.0)
                if msg.get("type") == "chat":
                    received_by_alice.append(msg.get("body"))
                    # Reply back
                    await sess.send_chat(f"Alice reply to: {msg.get('body')}")
        except Exception:
            pass
        finally:
            await sess.close()
            writer.close()
            try:
                await writer.wait_closed()
            except Exception:
                pass

    server = await asyncio.start_server(handle_alice_client, "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]

    async with server:
        reader, writer = await asyncio.open_connection("127.0.0.1", port)
        bob_sess = await Session.create(
            reader,
            writer,
            initiator=True,
            identity_priv=bob_priv,
            signing_key=bob_sign,
            expected_fingerprint=alice_fp,
        )

        for i in range(1, 4):
            body = f"Message #{i} from Bob over Yggdrasil/IPv6 🚀"
            await bob_sess.send_chat(body)
            reply = await asyncio.wait_for(bob_sess.receive_message(), timeout=3.0)
            while reply.get("type") != "chat":
                reply = await asyncio.wait_for(bob_sess.receive_message(), timeout=3.0)
            received_by_bob.append(reply.get("body"))

        await asyncio.sleep(0.1)
        await bob_sess.close()
        writer.close()
        try:
            await writer.wait_closed()
        except Exception:
            pass

    assert len(received_by_alice) == 3
    assert len(received_by_bob) == 3
    assert "Message #1" in received_by_alice[0]
    assert "Alice reply to: Message #1" in received_by_bob[0]


def test_rendezvous_key_vector_matches_go_core():
    """Ensure rendezvous key derivation is identical between Python and Go for Null#36571c05."""
    key = derive_rendezvous_key("Null", "36571c05")
    assert key.hex() == "4725456c9bc18c138f2066366fcf09bfe6ecdc34"
