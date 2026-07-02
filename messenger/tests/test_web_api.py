from fastapi.testclient import TestClient

from messenger.app.web_api import app


def test_health_endpoint():
    client = TestClient(app)
    response = client.get('/api/health')
    assert response.status_code == 200
    payload = response.json()
    assert payload['ok'] is True
    assert 'settings' in payload


def test_websocket_chat_flow():
    client = TestClient(app)
    with client.websocket_connect('/ws/chat') as ws:
        initial = ws.receive_json()
        assert initial['type'] == 'state'

        ws.send_json({'type': 'connect'})
        status = ws.receive_json()
        assert status['type'] == 'status'
        assert status['state'] == 'online'

        ws.send_json({'type': 'chat_message', 'body': 'hello'})
        msg1 = ws.receive_json()
        msg2 = ws.receive_json()
        messages = [msg1, msg2]
        assert all(m['type'] == 'message' for m in messages)
        assert any(m['body'] == 'hello' for m in messages)
        assert any(str(m['body']).startswith('Echo: ') for m in messages)
