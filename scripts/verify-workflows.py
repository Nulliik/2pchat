#!/usr/bin/env python3
"""
scripts/verify-workflows.py

Security Regression Linter for GitHub Actions workflows.
Detects dangerous direct interpolation of GitHub context/expressions (${{ ... }})
inside 'run:' blocks (CWE-78 Command Injection).

Allowed:
- Context expressions in 'env:', 'with:', 'if:', 'name:', 'concurrency:'.
- Shell environment variable expansion inside 'run:' (e.g., "$ENV_VAR", "${ENV_VAR}").

Forbidden:
- Direct interpolation of untrusted expressions (${{ github.* }}, ${{ inputs.* }}, etc.)
  inside 'run:' blocks.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS_DIR = REPO_ROOT / ".github/workflows"

# Pattern matching GitHub expression syntax: ${{ <expr> }}
EXPR_PATTERN = re.compile(r"\$\{\{\s*([^}]+?)\s*\}\}")

# Contexts that represent untrusted or externally-influenced input
DANGEROUS_PREFIXES = (
    "github.",
    "inputs.",
    "github.event.",
    "github.ref",
    "github.ref_name",
    "github.head_ref",
    "github.actor",
)


def find_run_blocks(content: str) -> list[tuple[int, str]]:
    """Find all 'run:' script blocks along with their start line numbers."""
    lines = content.splitlines()
    run_blocks: list[tuple[int, str]] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        # Match 'run:' or '- run:'
        m = re.search(r"^\s*(?:-\s+)?run:\s*(\|?>?)(.*)$", line)
        if m:
            start_line = i + 1
            modifier, remainder = m.group(1), m.group(2).strip()
            block_lines = []
            if remainder:
                block_lines.append(remainder)
            if modifier in ("|", ">"):
                # Multi-line block: determine indentation
                indent = len(line) - len(line.lstrip())
                i += 1
                while i < len(lines):
                    next_line = lines[i]
                    if not next_line.strip():
                        block_lines.append("")
                        i += 1
                        continue
                    next_indent = len(next_line) - len(next_line.lstrip())
                    if next_indent <= indent:
                        i -= 1
                        break
                    block_lines.append(next_line)
                    i += 1
            run_blocks.append((start_line, "\n".join(block_lines)))
        i += 1
    return run_blocks


def check_workflow(path: Path) -> list[dict]:
    findings = []
    content = path.read_text(encoding="utf-8")
    run_blocks = find_run_blocks(content)

    for start_line, block_text in run_blocks:
        matches = EXPR_PATTERN.findall(block_text)
        for match in matches:
            expr = match.strip()
            # Check if expression touches potentially untrusted contexts
            is_dangerous = any(expr.startswith(p) for p in DANGEROUS_PREFIXES) or "inputs." in expr or "github." in expr
            findings.append({
                "file": str(path.relative_to(REPO_ROOT)) if path.is_relative_to(REPO_ROOT) else str(path),
                "line": start_line,
                "expression": f"${{{{ {expr} }}}}",
                "dangerous": is_dangerous,
                "snippet": block_text.splitlines()[0][:100] if block_text else "",
            })
    return findings


def main() -> int:
    if not WORKFLOWS_DIR.is_dir():
        print(f"Error: {WORKFLOWS_DIR} not found", file=sys.stderr)
        return 2

    all_findings = []
    workflow_files = sorted(WORKFLOWS_DIR.glob("*.yml")) + sorted(WORKFLOWS_DIR.glob("*.yaml"))
    
    print(f"Checking {len(workflow_files)} workflow files for unsafe shell interpolations...")
    for wf in workflow_files:
        findings = check_workflow(wf)
        all_findings.extend(findings)

    if not all_findings:
        print("PASS: 0 direct ${{ ... }} interpolations found in 'run:' blocks.")
        return 0

    print(f"\nFAIL: Found {len(all_findings)} direct interpolation(s) in 'run:' blocks:")
    for f in all_findings:
        level = "ERROR" if f["dangerous"] else "WARNING"
        print(f"  [{level}] {f['file']}:{f['line']} -> {f['expression']}")
        print(f"          Snippet: {f['snippet']}")

    return 1 if any(f["dangerous"] for f in all_findings) else 0


if __name__ == "__main__":
    sys.exit(main())
