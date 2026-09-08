import subprocess, json, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GO = ROOT / "2pchatGO/android/core-go"
ANDROID = ROOT / "2pchatGO/android"

commands = [
    ("go-build", ["go","build","./..."], GO),
    ("go-test", ["go","test","./..."], GO),
    ("go-race", ["go","test","-race","./..."], GO),
    ("go-vet", ["go","vet","./..."], GO),
    ("android-unit", ["./gradlew","testDebugUnitTest"], ANDROID),
    ("android-apk", ["./gradlew","assembleDebug"], ANDROID),
]
results=[]
for name, cmd, cwd in commands:
    started=time.time()
    try:
        p=subprocess.run(cmd,cwd=cwd,text=True,capture_output=True,timeout=1800)
        status="PASS" if p.returncode==0 else "FAIL"
        results.append({
            "name":name,"status":status,"exit_code":p.returncode,
            "duration_sec":round(time.time()-started,2),
            "stdout_tail":p.stdout[-4000:],
            "stderr_tail":p.stderr[-4000:]
        })
    except Exception as e:
        results.append({"name":name,"status":"UNVERIFIED","error":str(e)})
out=ROOT/".agents/audit/latest/security-gate.json"
out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps(results,indent=2),encoding="utf-8")
print(json.dumps(results,indent=2))
