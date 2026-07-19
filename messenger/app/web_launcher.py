from __future__ import annotations

import argparse
import os
import shutil
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
WEBUI_DIR = PROJECT_ROOT / "webui"


def _resolve_npm() -> str:
    npm = shutil.which("npm")
    if npm:
        return npm

    nvm_npm = Path.home() / ".nvm" / "versions" / "node"
    if nvm_npm.exists():
        for candidate in sorted(nvm_npm.rglob("npm"), reverse=True):
            if candidate.is_file() and os.access(candidate, os.X_OK):
                return str(candidate)

    raise RuntimeError(
        "Unable to find npm in PATH. Install Node.js and ensure `npm` is available."
    )


def _backend_cmd(host: str, port: int) -> list[str]:
    return [
        sys.executable,
        "-m",
        "uvicorn",
        "messenger.app.web_api:app",
        "--host",
        host,
        "--port",
        str(port),
    ]


def _frontend_cmd(npm_path: str, host: str, port: int) -> list[str]:
    return [npm_path, "run", "dev", "--", "--host", host, "--port", str(port)]


def _pipe_output(prefix: str, stream) -> None:
    try:
        for line in iter(stream.readline, ""):
            if not line:
                break
            print(f"[{prefix}] {line.rstrip()}")
    finally:
        stream.close()


def _terminate(proc: subprocess.Popen, name: str) -> None:
    if proc.poll() is not None:
        return
    print(f"Stopping {name}...")
    proc.terminate()
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run 2P Chat web backend and frontend with one command"
    )
    parser.add_argument("--api-host", default="127.0.0.1")
    parser.add_argument("--api-port", type=int, default=8000)
    parser.add_argument("--web-host", default="127.0.0.1")
    parser.add_argument("--web-port", type=int, default=5173)
    parser.add_argument(
        "--install",
        action="store_true",
        help="Run npm install before starting frontend",
    )
    args = parser.parse_args()

    if not WEBUI_DIR.exists():
        raise RuntimeError(f"Frontend directory not found: {WEBUI_DIR}")

    npm_path = _resolve_npm()

    if args.install or not (WEBUI_DIR / "node_modules").exists():
        print("Installing frontend dependencies (npm install)...")
        install = subprocess.run([npm_path, "install"], cwd=WEBUI_DIR)
        if install.returncode != 0:
            return install.returncode

    backend_proc = subprocess.Popen(
        _backend_cmd(args.api_host, args.api_port),
        cwd=PROJECT_ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    frontend_proc = subprocess.Popen(
        _frontend_cmd(npm_path, args.web_host, args.web_port),
        cwd=WEBUI_DIR,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )

    threading.Thread(
        target=_pipe_output, args=("api", backend_proc.stdout), daemon=True
    ).start()
    threading.Thread(
        target=_pipe_output, args=("web", frontend_proc.stdout), daemon=True
    ).start()

    def _shutdown(*_args) -> None:
        _terminate(frontend_proc, "frontend")
        _terminate(backend_proc, "backend")

    signal.signal(signal.SIGINT, _shutdown)
    signal.signal(signal.SIGTERM, _shutdown)

    print(
        "2P Chat web launcher started. "
        f"Frontend: http://localhost:{args.web_port} | API: http://localhost:{args.api_port}"
    )

    try:
        while True:
            b_code = backend_proc.poll()
            f_code = frontend_proc.poll()
            if b_code is not None or f_code is not None:
                _shutdown()
                return b_code if b_code is not None else f_code
            time.sleep(0.2)
    except KeyboardInterrupt:
        _shutdown()
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
