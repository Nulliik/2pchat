# 2PChat local MCP

This server is intentionally narrow. It executes only predefined repository validation operations and refuses paths outside `TWO_P_CHAT_REPO`.

Set:
`TWO_P_CHAT_REPO=/absolute/path/to/2pchat`

Install:
`python -m pip install -r .agents/mcp/requirements.txt`

Run:
`python .agents/mcp/server.py`

Recommended tools:
- repo_status
- repo_diff_stat
- go_test
- go_race_test
- go_vet
- gradle_test
- gradle_assemble_debug
- pytest
- security_scan
- file_sha256

Do not add a generic `shell(command)` tool. Extend the allowlist with explicit operations instead.
