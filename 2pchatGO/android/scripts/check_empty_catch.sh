#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ -d "$ROOT_DIR/app/src/main/java" ]; then
    TARGET_DIR="$ROOT_DIR/app/src/main/java"
elif [ -d "$ROOT_DIR/android/app/src/main/java" ]; then
    TARGET_DIR="$ROOT_DIR/android/app/src/main/java"
elif [ -d "$ROOT_DIR/2pchatGO/android/app/src/main/java" ]; then
    TARGET_DIR="$ROOT_DIR/2pchatGO/android/app/src/main/java"
else
    echo "❌ Cannot find app/src/main/java directory"
    exit 1
fi

python3 - "$TARGET_DIR" << 'EOF'
import os
import re
import sys

target_dir = sys.argv[1]

# Match catch blocks that are either on a single line or multi-line
pattern = re.compile(r"catch\s*\((?:val\s+)?([_\w]+)\s*:\s*([A-Za-z0-9_]+)\)\s*\{([^}]*)\}", re.DOTALL)

unjustified_empty = []
justified_empty = 0

for root, _, files in os.walk(target_dir):
    for file in files:
        if file.endswith(".kt"):
            path = os.path.join(root, file)
            with open(path, "r", encoding="utf-8") as f:
                content = f.read()

            for match in pattern.finditer(content):
                body = match.group(3).strip()
                # Check if body is empty or contains no statements
                # Strip single-line and multi-line comments
                clean_body = re.sub(r"/\*.*?\*/", "", body, flags=re.DOTALL)
                clean_body = re.sub(r"//.*$", "", clean_body, flags=re.MULTILINE).strip()
                
                if not clean_body or clean_body == "":
                    # Body has no statements!
                    if "intentionally ignored" in body:
                        justified_empty += 1
                    else:
                        line_no = content[:match.start()].count("\n") + 1
                        rel_path = os.path.relpath(path, target_dir)
                        unjustified_empty.append((rel_path, line_no, match.group(0).replace("\n", " ")))

print(f"📊 Empty catch audit:")
print(f"   - Justified (with 'intentionally ignored'): {justified_empty}")
print(f"   - Unjustified empty: {len(unjustified_empty)}")

if unjustified_empty:
    print("⚠️  Warning: Found unjustified empty catch blocks:")
    for rel_path, line_no, snippet in unjustified_empty[:30]:
        print(f"     {rel_path}:{line_no}: {snippet[:90]}")
    if len(unjustified_empty) > 30:
        print(f"     ... and {len(unjustified_empty) - 30} more")
    # In CI this is currently a warning per plan M3 step 3:
    # "в CI пока warning (не fail), число выводить в лог. Через один релиз перевести в fail."
    sys.exit(0)
else:
    print("✅ All empty catch blocks are properly justified!")
    sys.exit(0)
EOF
