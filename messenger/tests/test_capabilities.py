import asyncio
import json

import pytest
import pytest_asyncio

from messenger.core import capabilities as caps
from messenger.core import protocol
from messenger.core.session import Session


@pytest.mark.parametrize("lv,lm,rv,rm,expected", [
    (1, 1, 1, 1, 1), (3, 1, 1, 1, 1), (1, 1, 4, 1, 1),
    (4, 2, 3, 3, 3), (3, 2, 1, 1, None), (1, 1, 4, 2, None),
])
def test_version_matrix(lv, lm, rv, rm, expected):
    local = dict(caps.local_declaration(), protocol_version=lv, min_supported_version=lm)
    remote = dict(caps.local_declaration(), protocol_version=rv, min_supported_version=rm)
    if expected is None:
        with pytest.raises(ValueError):
            caps.negotiate(local, remote)
    else:
        result = caps.negotiate(local, remote)
        assert result.protocol_version == expected
        assert result.peer_is_outdated == (rv < lv)


def test_intersection_canonical_and_unknown_features():
    local, remote = caps.local_declaration(), caps.local_declaration()
    local["capabilities"] += ["future_feature_v99", "group_suite_v2"]
    remote["capabilities"] += ["future_feature_v99"]
    assert caps.negotiate(local, remote).active_capabilities == caps.BASELINE
    before = caps.canonical(remote)
    remote["capabilities"].reverse()
    assert caps.canonical(remote) == before


@pytest.mark.parametrize("patch", [
    {"protocol_version": True}, {"protocol_version": 1.5},
    {"protocol_version": 65536}, {"min_supported_version": 2},
    {"capabilities": None}, {"capabilities": ["Upper_v1"]},
    {"capabilities": ["x_v1", "x_v1"]}, {"capabilities": ["x" * 65 + "_v1"]},
    {"capabilities": [f"x_{i}_v1" for i in range(65)]},
])
def test_invalid_declarations(patch):
    with pytest.raises(ValueError):
        caps.negotiate(caps.local_declaration(), dict(caps.local_declaration(), **patch))


@pytest_asyncio.fixture
async def peers(monkeypatch, tmp_path):
    monkeypatch.setenv("P2PCHAT_CONFIG_DIR", str(tmp_path))
    accepted = asyncio.get_running_loop().create_future()

    async def accept(reader, writer):
        try:
            accepted.set_result(await Session.create(reader, writer, initiator=False))
        except Exception as exc:
            accepted.set_exception(exc)

    server = await asyncio.start_server(accept, "127.0.0.1", 0)
    reader, writer = await asyncio.open_connection("127.0.0.1", server.sockets[0].getsockname()[1])
    a = await Session.create(reader, writer, initiator=True)
    b = await asyncio.wait_for(accepted, 3)
    try:
        yield a, b
    finally:
        await a.close()
        await b.close()
        server.close()
        await server.wait_closed()


@pytest.mark.asyncio
async def test_authenticated_exchange_and_chat(peers):
    a, b = peers
    assert a.negotiated_protocol is None
    await a.send_reliable({"type": "identity_info"})
    await b.send_reliable({"type": "identity_info"})
    for peer in peers:
        assert peer.negotiated_protocol.active_capabilities == caps.BASELINE
        assert (await peer.receive_message())["type"] == "identity_info"
    await a.send_chat("hello across versions")
    assert (await b.receive_message())["body"] == "hello across versions"
    with pytest.raises(ValueError, match="group protocol"):
        await a.send_reliable({"type": "group_future_v9"})


@pytest.mark.asyncio
async def test_legacy_and_session_change_rejected(peers):
    a, b = peers
    await a._send_plaintext(protocol.encode_message({"type": "identity_info"}),
                            message_type="identity_info", message_ref="legacy")
    assert (await b.receive_message())["type"] == "identity_info"
    assert b.negotiated_protocol.peer_is_legacy
    await a.send_chat("legacy chat")
    assert (await b.receive_message())["body"] == "legacy chat"
    # A declaration cannot be silently replaced during a live ratchet session.
    raw = protocol.encode_message({"type": "identity_info", "protocol": caps.local_declaration()})
    await a._send_plaintext(raw, message_type="identity_info", message_ref="changed")
    message = await asyncio.wait_for(b.receive_message(), 2)
    assert message["state"] == "offline"
    assert "changed" in message["reason"]
    assert b.negotiated_protocol is None


@pytest.mark.asyncio
@pytest.mark.parametrize("remote", [None, {
    "protocol_version": 2, "min_supported_version": 2, "capabilities": sorted(caps.BASELINE),
}])
async def test_invalid_or_incompatible_closes_session(peers, remote):
    a, b = peers
    raw = json.dumps({"type": "identity_info", "protocol": remote}).encode()
    await a._send_plaintext(raw, message_type="identity_info", message_ref="invalid")
    result = await asyncio.wait_for(b.receive_message(), 2)
    assert result["state"] == "offline"
    assert b.writer.is_closing()


def test_duplicate_fields_rejected():
    with pytest.raises(ValueError, match="duplicate"):
        json.loads('{"protocol":{},"protocol":null}', object_pairs_hook=caps.unique_fields)
