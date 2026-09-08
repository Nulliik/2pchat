import shutil, subprocess, os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
checks = [
    ("git", ["git", "--version"]),
    ("go", ["go", "version"]),
    ("python", ["python", "--version"]),
    ("gitleaks", ["gitleaks", "version"]),
    ("semgrep", ["semgrep", "--version"]),
    ("trivy", ["trivy", "--version"]),
]
for name, cmd in checks:
    if not shutil.which(cmd[0]):
        print(f"UNAVAILABLE {name}")
        continue
    p = subprocess.run(cmd, cwd=ROOT, text=True, capture_output=True)
    print(f"{'AVAILABLE' if p.returncode == 0 else 'ERROR'} {name}: {(p.stdout or p.stderr).strip()[:200]}")

android = ROOT / "2pchatGO/android"
print(f"Android tree: {'FOUND' if android.exists() else 'MISSING'}")
print(f"Go core: {'FOUND' if (android/'core-go').exists() else 'MISSING'}")
