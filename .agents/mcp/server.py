import os
import subprocess
from pathlib import Path
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("2pchat-local")

ROOT = Path(os.environ.get("TWO_P_CHAT_REPO", ".")).resolve()

def safe_path(rel: str) -> Path:
    p = (ROOT / rel).resolve()
    if p != ROOT and ROOT not in p.parents:
        raise ValueError("path escapes repository")
    return p

def run(args, cwd):
    p = subprocess.run(args, cwd=str(cwd), text=True, capture_output=True, timeout=900)
    return {
        "command": " ".join(args),
        "cwd": str(cwd),
        "exit_code": p.returncode,
        "stdout": p.stdout[-20000:],
        "stderr": p.stderr[-20000:],
        "status": "PASS" if p.returncode == 0 else "FAIL",
    }

@mcp.tool()
def repo_status():
    return run(["git", "status", "--short", "--branch"], ROOT)

@mcp.tool()
def repo_diff_stat():
    return run(["git", "diff", "--stat"], ROOT)

@mcp.tool()
def go_test(race: bool = False):
    cwd = safe_path("2pchatGO/android/core-go")
    return run(["go", "test", "-race", "./..."] if race else ["go", "test", "./..."], cwd)

@mcp.tool()
def go_vet():
    return run(["go", "vet", "./..."], safe_path("2pchatGO/android/core-go"))

@mcp.tool()
def go_build():
    return run(["go", "build", "./..."], safe_path("2pchatGO/android/core-go"))

@mcp.tool()
def gradle_test():
    return run(["./gradlew", "testDebugUnitTest"], safe_path("2pchatGO/android"))

@mcp.tool()
def gradle_assemble_debug():
    return run(["./gradlew", "assembleDebug"], safe_path("2pchatGO/android"))

@mcp.tool()
def pytest():
    return run(["python", "-m", "pytest"], ROOT)

@mcp.tool()
def security_scan():
    results = []
    for cmd in [
        ["gitleaks", "detect", "--no-banner"],
        ["semgrep", "scan", "--config", "auto"],
    ]:
        try:
            results.append(run(cmd, ROOT))
        except FileNotFoundError:
            results.append({"command": " ".join(cmd), "status": "UNVERIFIED", "reason": "tool unavailable"})
    return results

@mcp.tool()
def file_sha256(path: str):
    import hashlib
    p = safe_path(path)
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return {"path": str(p.relative_to(ROOT)), "sha256": h.hexdigest()}

if __name__ == "__main__":
    mcp.run()
