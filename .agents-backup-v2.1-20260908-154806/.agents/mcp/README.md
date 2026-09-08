# 2PChat Engineering MCP

This is a deliberately narrow local MCP server.

## Why narrow?

MCP tools can execute real operations. Do not expose an unrestricted shell to the model. This server uses an allowlist of developer/security commands.

The server is intended to provide:
- Go validation
- Android/Gradle validation
- Python tests
- static/security scans
- read-only PCAP analysis
- repository status/diff inspection
- basic cryptographic verification utilities

## Install

From the repository root:

```bash
python3 -m venv .agents/mcp/.venv
source .agents/mcp/.venv/bin/activate
python -m pip install -r .agents/mcp/requirements.txt
```

Then ensure the desired external tools are installed separately:
- Go
- staticcheck
- govulncheck
- Gradle/JDK for Android
- pytest where applicable
- semgrep
- gitleaks
- trivy
- tshark

## Repository binding

Set:

```bash
export TWO_P_CHAT_REPO="/absolute/path/to/2PChat"
```

The server refuses paths outside that repository.

## Security

Keep this MCP local.

Do not expose it over the network without adding authentication and an explicit threat model.
