import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from messenger.app.web_api import (
    ALLOWED_ORIGINS,
    MAX_WEBSOCKET_MESSAGE_SIZE,
    SESSION_TOKEN,
    app,
    state,
)


def _ws_url(token: str = SESSION_TOKEN) -> str:
    return f"/ws/chat?token={token}"


def _ws_headers(origin: str = ALLOWED_ORIGINS[0]) -> dict[str, str]:
    return {"origin": origin}


def test_health_endpoint():
    client = TestClient(app)
    response = client.get("/api/health")
    assert response.status_code == 200
    payload = response.json()
    assert payload["ok"] is True
    assert "settings" in payload


def test_session_bootstrap_rejects_untrusted_browser_origin():
    client = TestClient(app)
    rejected = client.get("/api/session", headers={"origin": "https://attacker.invalid"})
    assert rejected.status_code == 403
    accepted = client.get("/api/session", headers={"origin": ALLOWED_ORIGINS[0]})
    assert accepted.json()["token"] == SESSION_TOKEN


def test_websocket_rejects_unknown_origin_and_missing_token():
    client = TestClient(app)
    with pytest.raises(WebSocketDisconnect) as rejected_origin:
        with client.websocket_connect(
            _ws_url(), headers=_ws_headers("https://attacker.invalid")
        ):
            pass
    assert rejected_origin.value.code == 1008

    with pytest.raises(WebSocketDisconnect) as missing_token:
        with client.websocket_connect("/ws/chat", headers=_ws_headers()):
            pass
    assert missing_token.value.code == 1008


def test_websocket_chat_flow_and_strict_settings():
    client = TestClient(app)
    state.online = False
    state.settings["nickname"] = "You"
    with client.websocket_connect(_ws_url(), headers=_ws_headers()) as ws:
        initial = ws.receive_json()
        assert initial["type"] == "state"

        ws.send_json({"type": "settings_update", "settings": {"nickname": "Neo 2"}})
        settings = ws.receive_json()
        assert settings["settings"]["nickname"] == "Neo 2"

        ws.send_json({"type": "settings_update", "settings": {"admin": True}})
        assert ws.receive_json()["type"] == "error"
        assert "admin" not in state.settings

        ws.send_json({"type": "settings_update", "settings": {"nickname": "<script>"}})
        assert ws.receive_json()["type"] == "error"

        ws.send_json({"type": "connect"})
        status = ws.receive_json()
        assert status["type"] == "status"
        assert status["state"] == "online"

        ws.send_json({"type": "chat_message", "body": "hello"})
        messages = [ws.receive_json(), ws.receive_json()]
        assert all(message["type"] == "message" for message in messages)
        assert any(message["body"] == "hello" for message in messages)
        assert any(str(message["body"]).startswith("Echo: ") for message in messages)


def test_websocket_rejects_oversized_message():
    client = TestClient(app)
    with client.websocket_connect(_ws_url(), headers=_ws_headers()) as ws:
        ws.receive_json()
        ws.send_text("x" * (MAX_WEBSOCKET_MESSAGE_SIZE + 1))
        with pytest.raises(WebSocketDisconnect) as closed:
            ws.receive_json()
    assert closed.value.code == 1009
