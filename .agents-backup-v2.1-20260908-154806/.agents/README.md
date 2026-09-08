# 2PChat AI Engineering Pack

This directory is designed for Google Antigravity.

## Layout

- `../AGENTS.md` — repository-wide mandatory rules
- `skills/` — specialized workflows
- `agents/` — independent reviewer roles
- `mcp/` — local security/development MCP server
- `mcp_config.json` — workspace MCP configuration

## Recommended workflow

For ordinary coding:
1. Read AGENTS.md.
2. Select the relevant skill.
3. Implement the smallest safe change.
4. Run relevant validation.
5. Review the diff.

For security-sensitive changes:
1. Use `2pchat-security-audit`.
2. Use `2pchat-crypto` for protocol/crypto work.
3. Run security MCP tools.
4. Ask an independent reviewer agent to challenge the change.
5. Fix findings.
6. Re-run validation.

Do not treat the absence of findings as proof of security.
