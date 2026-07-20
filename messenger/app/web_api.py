from __future__ import annotations

import hmac
import json
import os
import secrets
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware


MAX_WEBSOCKET_MESSAGE_SIZE = 1024 * 1024
MAX_CHAT_MESSAGE_SIZE = 64 * 1024
SESSION_TOKEN = os.environ.get("P2PCHAT_WEB_SESSION_TOKEN") or secrets.token_urlsafe(32)

ALLOWED_ORIGINS = (
    "http://localhost:5173",
    "http://127.0.0.1:5173",
    "http://localhost:8000",
    "http://127.0.0.1:8000",
)

ALLOWED_MODES = frozenset({"connect", "listen", "rendezvous"})
ALLOWED_TRANSPORTS = frozenset({"direct", "ygg", "ygg-embedded"})


@dataclass
class ChatState:
    online: bool = False
    settings: dict[str, Any] = field(
        default_factory=lambda: {
            "nickname": "You",
            "mode": "connect",
            "host": "127.0.0.1",
            "bind": "0.0.0.0",
            "port": 4444,
            "transport": "direct",
        }
    )


class ConnectionManager:
    def __init__(self) -> None:
        self.active: set[WebSocket] = set()

    async def connect(self, websocket: WebSocket) -> None:
        await websocket.accept()
        self.active.add(websocket)

    def disconnect(self, websocket: WebSocket) -> None:
        self.active.discard(websocket)

    async def broadcast(self, payload: dict[str, Any]) -> None:
        dead: list[WebSocket] = []
        message = json.dumps(payload)
        for ws in tuple(self.active):
            try:
                await ws.send_text(message)
            except (RuntimeError, WebSocketDisconnect):
                dead.append(ws)
        for ws in dead:
            self.disconnect(ws)


def _valid_nickname(value: Any) -> str:
    if not isinstance(value, str):
        raise ValueError("nickname must be a string")
    nickname = value.strip()
    if not 1 <= len(nickname) <= 30:
        raise ValueError("nickname must contain 1 to 30 characters")
    if any(not (char.isalnum() or char == " ") for char in nickname):
        raise ValueError("nickname may contain only letters, numbers, and spaces")
    return nickname


def _validate_settings(incoming: Any) -> dict[str, Any]:
    if not isinstance(incoming, dict):
        raise ValueError("settings must be an object")
    unknown = set(incoming) - {"nickname", "mode", "host", "bind", "port", "transport"}
    if unknown:
        raise ValueError(f"unsupported setting: {sorted(unknown)[0]}")

    validated: dict[str, Any] = {}
    if "nickname" in incoming:
        validated["nickname"] = _valid_nickname(incoming["nickname"])
    for key in ("host", "bind"):
        if key in incoming:
            value = incoming[key]
            if not isinstance(value, str) or not 1 <= len(value) <= 253:
                raise ValueError(f"{key} must be a non-empty string of at most 253 characters")
            if any(char.isspace() or ord(char) < 32 for char in value):
                raise ValueError(f"{key} contains invalid characters")
            validated[key] = value
    if "port" in incoming:
        port = incoming["port"]
        if isinstance(port, bool) or not isinstance(port, int) or not 1 <= port <= 65535:
            raise ValueError("port must be an integer between 1 and 65535")
        validated["port"] = port
    if "mode" in incoming:
        if incoming["mode"] not in ALLOWED_MODES:
            raise ValueError("unsupported mode")
        validated["mode"] = incoming["mode"]
    if "transport" in incoming:
        if incoming["transport"] not in ALLOWED_TRANSPORTS:
            raise ValueError("unsupported transport")
        validated["transport"] = incoming["transport"]
    return validated


async def _receive_event(websocket: WebSocket) -> dict[str, Any]:
    message = await websocket.receive()
    if message["type"] == "websocket.disconnect":
        raise WebSocketDisconnect(message.get("code", 1000))
    raw = message.get("text")
    if raw is None and message.get("bytes") is not None:
        try:
            raw = message["bytes"].decode("utf-8")
        except UnicodeDecodeError as exc:
            raise ValueError("message must be UTF-8 JSON") from exc
    if raw is None:
        raise ValueError("message must contain JSON text")
    if len(raw.encode("utf-8")) > MAX_WEBSOCKET_MESSAGE_SIZE:
        raise OverflowError("message exceeds the 1 MiB limit")
    try:
        event = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ValueError("invalid JSON") from exc
    if not isinstance(event, dict):
        raise ValueError("event must be an object")
    return event


app = FastAPI(title="2P Chat Web API")
app.add_middleware(
    CORSMiddleware,
    allow_origins=list(ALLOWED_ORIGINS),
    allow_credentials=True,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["*"],
)

state = ChatState()
manager = ConnectionManager()


@app.get("/api/health")
def health() -> dict[str, Any]:
    return {"ok": True, "online": state.online, "settings": state.settings}


@app.get("/api/session")
def web_session(request: Request) -> dict[str, str]:
    origin = request.headers.get("origin")
    if origin is not None and origin not in ALLOWED_ORIGINS:
        raise HTTPException(status_code=403, detail="origin rejected")
    return {"token": SESSION_TOKEN}


@app.websocket("/ws/chat")
async def chat_socket(websocket: WebSocket) -> None:
    origin = websocket.headers.get("origin", "")
    token = websocket.query_params.get("token", "")
    if origin not in ALLOWED_ORIGINS or not hmac.compare_digest(token, SESSION_TOKEN):
        await websocket.close(code=1008, reason="origin or session token rejected")
        return

    await manager.connect(websocket)
    await websocket.send_json(
        {
            "type": "state",
            "online": state.online,
            "settings": state.settings,
        }
    )
    try:
        while True:
            try:
                event = await _receive_event(websocket)
            except OverflowError as exc:
                await websocket.close(code=1009, reason=str(exc))
                return
            except ValueError as exc:
                await websocket.send_json({"type": "error", "message": str(exc)})
                continue

            etype = event.get("type")
            if etype == "settings_update":
                try:
                    incoming = _validate_settings(event.get("settings"))
                except ValueError as exc:
                    await websocket.send_json({"type": "error", "message": str(exc)})
                    continue
                state.settings.update(incoming)
                await manager.broadcast({"type": "settings", "settings": state.settings})
                continue

            if etype == "connect":
                state.online = True
                await manager.broadcast(
                    {
                        "type": "status",
                        "state": "online",
                        "message": "Connected",
                        "timestamp": datetime.now(timezone.utc).isoformat(),
                    }
                )
                continue

            if etype == "disconnect":
                state.online = False
                await manager.broadcast(
                    {
                        "type": "status",
                        "state": "offline",
                        "message": "Disconnected",
                        "timestamp": datetime.now(timezone.utc).isoformat(),
                    }
                )
                continue

            if etype == "chat_message":
                raw_body = event.get("body")
                if not isinstance(raw_body, str):
                    await websocket.send_json(
                        {"type": "error", "message": "message body must be a string"}
                    )
                    continue
                body = raw_body.strip()
                if not body:
                    continue
                if len(body.encode("utf-8")) > MAX_CHAT_MESSAGE_SIZE:
                    await websocket.send_json(
                        {"type": "error", "message": "message body exceeds the 64 KiB limit"}
                    )
                    continue
                author = state.settings.get("nickname", "You")
                await manager.broadcast(
                    {
                        "type": "message",
                        "author": author,
                        "body": body,
                        "timestamp": datetime.now(timezone.utc).isoformat(),
                    }
                )
                await manager.broadcast(
                    {
                        "type": "message",
                        "author": "Peer",
                        "body": f"Echo: {body}",
                        "timestamp": datetime.now(timezone.utc).isoformat(),
                    }
                )
    except WebSocketDisconnect:
        pass
    finally:
        manager.disconnect(websocket)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        app,
        host="127.0.0.1",
        port=8000,
        reload=False,
        ws_max_size=MAX_WEBSOCKET_MESSAGE_SIZE,
    )
