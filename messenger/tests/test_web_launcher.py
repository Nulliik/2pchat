import sys

from messenger.app import web_launcher


def test_backend_cmd_builds_uvicorn_invocation():
    cmd = web_launcher._backend_cmd("127.0.0.1", 9000)
    assert cmd[0] == sys.executable
    assert cmd[1:4] == ["-m", "uvicorn", "messenger.app.web_api:app"]
    assert cmd[-4:] == ["--host", "127.0.0.1", "--port", "9000"]


def test_frontend_cmd_builds_vite_invocation():
    cmd = web_launcher._frontend_cmd("npm", "0.0.0.0", 5173)
    assert cmd == [
        "npm",
        "run",
        "dev",
        "--",
        "--host",
        "0.0.0.0",
        "--port",
        "5173",
    ]
