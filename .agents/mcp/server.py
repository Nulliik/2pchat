#!/usr/bin/env python3
"""
2PChat local MCP server.

Design goals:
- read-only or narrowly allowlisted developer operations
- no arbitrary shell tool
- repository path is explicitly configured
- security scanners are invoked only through fixed commands
- tool descriptions tell the model what is and is not verified

Run:
    python .agents/mcp/server.py
"""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
from pathlib import Path
from typing import Optional

from mcp.server.fastmcp import FastMCP

mcp = FastMCP("2pchat-engineering")

REPO = Path(os.environ.get("TWO_P_CHAT_REPO", os.getcwd())).resolve()
MAX_OUTPUT = 30000


def _safe_path(rel: str = ".") -> Path:
    p = (REPO / rel).resolve()
    if p != REPO and REPO not in p.parents:
        raise ValueError("Path escapes configured repository")
    return p


def _run(argv: list[str], cwd: Optional[Path] = None, timeout: int = 180) -> dict:
    try:
        proc = subprocess.run(
            argv,
            cwd=str(cwd or REPO),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout,
            check=False,
        )
        out = proc.stdout[-MAX_OUTPUT:]
        return {"exit_code": proc.returncode, "output": out}
    except FileNotFoundError:
        return {"exit_code": 127, "output": f"Tool not found: {argv[0]}"}
    except subprocess.TimeoutExpired:
        return {"exit_code": 124, "output": f"Timed out after {timeout}s: {' '.join(argv)}"}


@mcp.tool()
def repo_status() -> dict:
    """Return git status. Read-only."""
    return _run(["git", "status", "--short"])


@mcp.tool()
def repo_diff_stat() -> dict:
    """Return git diff statistics. Read-only."""
    return _run(["git", "diff", "--stat"])


def _find_go_dir() -> Path:
    direct = REPO / "go.mod"
    if direct.is_file():
        return REPO
    candidates = list(REPO.glob("**/go.mod"))
    if candidates:
        return candidates[0].parent
    return REPO

def _find_gradle_dir() -> Optional[Path]:
    direct = REPO / "gradlew"
    if direct.is_file():
        return REPO
    candidates = list(REPO.glob("**/gradlew"))
    if candidates:
        return candidates[0].parent
    return None

@mcp.tool()
def go_test(race: bool = False) -> dict:
    """Run Go tests. Uses the fixed command go test ./... and optional -race."""
    cmd = ["go", "test"]
    if race:
        cmd.append("-race")
    cmd.append("./...")
    return _run(cmd, cwd=_find_go_dir(), timeout=600)


@mcp.tool()
def go_vet() -> dict:
    """Run go vet ./... ."""
    return _run(["go", "vet", "./..."], cwd=_find_go_dir(), timeout=300)


@mcp.tool()
def staticcheck() -> dict:
    """Run staticcheck ./... if installed."""
    return _run(["staticcheck", "./..."], cwd=_find_go_dir(), timeout=600)


@mcp.tool()
def govulncheck() -> dict:
    """Run govulncheck ./... if installed."""
    return _run(["govulncheck", "./..."], cwd=_find_go_dir(), timeout=600)


@mcp.tool()
def gradle_test() -> dict:
    """Run ./gradlew test if a Gradle wrapper exists."""
    gdir = _find_gradle_dir()
    if not gdir:
        return {"exit_code": 2, "output": "gradlew not found"}
    return _run(["./gradlew", "test"], cwd=gdir, timeout=900)


@mcp.tool()
def gradle_lint() -> dict:
    """Run ./gradlew lint if a Gradle wrapper exists."""
    gdir = _find_gradle_dir()
    if not gdir:
        return {"exit_code": 2, "output": "gradlew not found"}
    return _run(["./gradlew", "lint"], cwd=gdir, timeout=900)


@mcp.tool()
def gradle_assemble_debug() -> dict:
    """Run ./gradlew assembleDebug if a Gradle wrapper exists."""
    gdir = _find_gradle_dir()
    if not gdir:
        return {"exit_code": 2, "output": "gradlew not found"}
    return _run(["./gradlew", "assembleDebug"], cwd=gdir, timeout=1200)


@mcp.tool()
def pytest() -> dict:
    """Run pytest. Only use when the repository contains Python tests."""
    return _run(["pytest"], timeout=900)


@mcp.tool()
def semgrep_scan() -> dict:
    """Run Semgrep with automatic rules in the repository. Read-only analysis."""
    return _run(["semgrep", "--config", "auto", "--error", "."], timeout=900)


@mcp.tool()
def gitleaks_scan() -> dict:
    """Run a read-only Gitleaks scan against the working tree."""
    return _run(["gitleaks", "detect", "--no-banner", "--redact"], timeout=600)


@mcp.tool()
def trivy_fs_scan() -> dict:
    """Run Trivy filesystem vulnerability/misconfiguration scan."""
    return _run(["trivy", "fs", "--scanners", "vuln,misconfig,secret", "."], timeout=900)


@mcp.tool()
def tshark_read_pcap(relative_pcap: str, display_filter: str = "") -> dict:
    """
    Read/analyze an existing PCAP with tshark.
    This tool never captures traffic and never sends packets.
    """
    pcap = _safe_path(relative_pcap)
    if not pcap.is_file():
        return {"exit_code": 2, "output": "PCAP file not found"}
    cmd = ["tshark", "-r", str(pcap)]
    if display_filter:
        cmd += ["-Y", display_filter]
    cmd += ["-T", "fields", "-e", "frame.number", "-e", "frame.time",
            "-e", "ip.src", "-e", "ip.dst", "-e", "_ws.col.Protocol",
            "-e", "_ws.col.Info"]
    return _run(cmd, timeout=300)


@mcp.tool()
def file_sha256(relative_path: str) -> dict:
    """Calculate SHA-256 for an existing repository file."""
    p = _safe_path(relative_path)
    if not p.is_file():
        return {"error": "file not found"}
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return {"path": str(p.relative_to(REPO)), "sha256": h.hexdigest()}


@mcp.tool()
def crypto_hash(algorithm: str, data_utf8: str) -> dict:
    """
    Hash data using Python's standard hashlib.
    This is a verification utility, not a protocol implementation.
    """
    allowed = {"sha256", "sha384", "sha512", "sha3_256", "sha3_512"}
    if algorithm not in allowed:
        return {"error": f"Unsupported verification hash: {algorithm}"}
    h = hashlib.new(algorithm)
    h.update(data_utf8.encode("utf-8"))
    return {"algorithm": algorithm, "hex": h.hexdigest()}


if __name__ == "__main__":
    mcp.run(transport="stdio")
