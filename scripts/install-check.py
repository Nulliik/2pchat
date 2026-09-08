from pathlib import Path
root=Path(__file__).resolve().parents[1]
required=[
    ".agents/agents/2pchat-orchestrator/agent.md",
    ".agents/agents/2pchat-crypto/agent.md",
    ".agents/agents/2pchat-jni/agent.md",
    ".agents/skills/2pchat-security-gate/SKILL.md",
    ".agents/knowledge/architecture/system-map.yaml",
    ".agents/policies/security-gate.yaml",
    ".agents/mcp/server.py",
    "AGENTS.md",
]
for x in required:
    p=root/x
    print(("PASS" if p.exists() else "FAIL"), x)
