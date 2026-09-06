#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

python3 -m venv .agents/mcp/.venv
source .agents/mcp/.venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r .agents/mcp/requirements.txt

echo
echo "2PChat AI Engineering Pack MCP environment installed."
echo "Optional external tools:"
echo "  go, staticcheck, govulncheck"
echo "  JDK + Android SDK"
echo "  pytest"
echo "  semgrep, gitleaks, trivy, tshark"
