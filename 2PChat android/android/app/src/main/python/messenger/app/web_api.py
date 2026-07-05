from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware


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
        for ws in self.active:
            try:
                await ws.send_text(message)
            except RuntimeError:
                dead.append(ws)
        for ws in dead:
            self.disconnect(ws)


app = FastAPI(title="2P Chat Web API")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

state = ChatState()
manager = ConnectionManager()


@app.get("/api/health")
def health() -> dict[str, Any]:
    return {"ok": True, "online": state.online, "settings": state.settings}


@app.websocket("/ws/chat")
async def chat_socket(websocket: WebSocket) -> None:
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
            event = await websocket.receive_json()
            etype = event.get("type")
            if etype == "settings_update":
                incoming = event.get("settings", {})
                if isinstance(incoming, dict):
                    state.settings.update(incoming)
                await manager.broadcast(
                    {
                        "type": "settings",
                        "settings": state.settings,
                    }
                )
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
                body = (event.get("body") or "").strip()
                if not body:
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
        manager.disconnect(websocket)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8000, reload=False)
