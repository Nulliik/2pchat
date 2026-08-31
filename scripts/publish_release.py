#!/usr/bin/env python3
"""
Automated Dual-Release Publisher for 2PChat
Publishes APK releases simultaneously to:
1. Public releases repository (kodzyfox/2pchat-releases) for in-app self-updater
2. Primary development repository (Nulliik/2pchat) for repository sidebar & tags
"""

import sys
import os
import re
import json
import subprocess
import urllib.request
import urllib.parse
import ssl

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANDROID_DIR = os.path.join(REPO_ROOT, "2pchatGO", "android")
BUILD_GRADLE = os.path.join(ANDROID_DIR, "app", "build.gradle.kts")
APK_PATH = os.path.join(ANDROID_DIR, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
TOKEN_FILE = os.path.expanduser("~/.config/2pchat/github_token")

REPOS_TO_PUBLISH = [
    ("kodzyfox", "2pchat-releases"),
    ("Nulliik", "2pchat"),
]

def get_github_token() -> str:
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        return token.strip()

    if os.path.exists(TOKEN_FILE):
        with open(TOKEN_FILE, "r") as f:
            t = f.read().strip()
            if t:
                return t

    try:
        proc = subprocess.run(
            ["git", "credential", "fill"],
            input="protocol=https\nhost=github.com\n",
            text=True,
            capture_output=True,
            cwd=REPO_ROOT
        )
        if proc.returncode == 0:
            for line in proc.stdout.splitlines():
                if line.startswith("password="):
                    pwd = line.split("=", 1)[1].strip()
                    if pwd.startswith("ghp_") or pwd.startswith("github_pat_"):
                        save_token(pwd)
                        return pwd
    except Exception:
        pass

    print("\n🔑 Для автоматической публикации введите ваш GitHub Token (ghp_...):")
    token = input("GitHub Token: ").strip()
    if token:
        save_token(token)
        return token

    print("❌ Токен не предоставлен. Отмена.")
    sys.exit(1)

def save_token(token: str):
    os.makedirs(os.path.dirname(TOKEN_FILE), exist_ok=True)
    with open(TOKEN_FILE, "w") as f:
        f.write(token.strip())
    os.chmod(TOKEN_FILE, 0o600)

def update_version_in_gradle(version_name: str) -> int:
    with open(BUILD_GRADLE, "r") as f:
        content = f.read()

    vc_match = re.search(r'versionCode\s*=\s*(\d+)', content)
    curr_code = int(vc_match.group(1)) if vc_match else 10
    new_code = curr_code + 1

    content = re.sub(r'versionCode\s*=\s*\d+', f'versionCode = {new_code}', content)
    content = re.sub(r'versionName\s*=\s*"[^"]+"', f'versionName = "{version_name}"', content)

    with open(BUILD_GRADLE, "w") as f:
        f.write(content)

    print(f"📦 Обновлена версия в build.gradle.kts: v{version_name} (code {new_code})")
    return new_code

def build_apk():
    print("🔨 Сборка APK через Gradle...")
    res = subprocess.run(["./gradlew", "assembleDebug"], cwd=ANDROID_DIR)
    if res.returncode != 0 or not os.path.exists(APK_PATH):
        print("❌ Ошибка при сборке APK.")
        sys.exit(1)
    size_mb = os.path.getsize(APK_PATH) / (1024 * 1024)
    print(f"✅ APK успешно собран: {size_mb:.1f} MB")

def upload_release_to_github(owner: str, repo: str, tag: str, title: str, body: str, apk_path: str, token: str):
    print(f"🚀 Публикация релиза в https://github.com/{owner}/{repo}...")
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE

    create_url = f"https://api.github.com/repos/{owner}/{repo}/releases"
    headers = {
        "User-Agent": "2PChat-Release-Bot",
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github.v3+json",
        "Content-Type": "application/json",
    }

    payload = json.dumps({
        "tag_name": tag,
        "name": title,
        "body": body,
        "draft": False,
        "prerelease": False
    }).encode("utf-8")

    upload_url_template = None

    try:
        req = urllib.request.Request(create_url, data=payload, headers=headers, method="POST")
        with urllib.request.urlopen(req, context=ctx) as resp:
            data = json.loads(resp.read().decode())
            upload_url_template = data.get("upload_url")
    except urllib.error.HTTPError as e:
        if e.code == 422:
            try:
                get_url = f"https://api.github.com/repos/{owner}/{repo}/releases/tags/{tag}"
                req = urllib.request.Request(get_url, headers=headers)
                with urllib.request.urlopen(req, context=ctx) as resp:
                    data = json.loads(resp.read().decode())
                    upload_url_template = data.get("upload_url")
            except Exception as e2:
                print(f"⚠️ Не удалось получить существующий релиз в {owner}/{repo}: {e2}")
                return
        else:
            print(f"⚠️ Ошибка создания релиза в {owner}/{repo}: HTTP {e.code}")
            return

    if not upload_url_template:
        print(f"⚠️ Не найден upload_url для {owner}/{repo}")
        return

    upload_url = upload_url_template.split("{")[0] + f"?name=app-debug.apk"
    with open(apk_path, "rb") as f:
        apk_data = f.read()

    upload_headers = {
        "User-Agent": "2PChat-Release-Bot",
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/vnd.android.package-archive",
        "Content-Length": str(len(apk_data)),
    }

    try:
        req = urllib.request.Request(upload_url, data=apk_data, headers=upload_headers, method="POST")
        with urllib.request.urlopen(req, context=ctx) as resp:
            print(f"🎉 Релиз {tag} успешно опубликован в {owner}/{repo}!")
    except urllib.error.HTTPError as e:
        if e.code == 422:
            print(f"ℹ️ Файл app-debug.apk уже прикреплен к релизу в {owner}/{repo}.")
        else:
            print(f"⚠️ Ошибка загрузки APK в {owner}/{repo}: HTTP {e.code}")

def main():
    if len(sys.argv) < 2:
        print("Использование: ./release.sh <версия> [описание изменений]")
        print("Пример: ./release.sh 0.0.8.3 \"Обновление интерфейса и стабильности\"")
        sys.exit(1)

    ver = sys.argv[1].lstrip("vV")
    tag = f"v{ver}"
    notes = sys.argv[2] if len(sys.argv) > 2 else f"Release {tag}"

    token = get_github_token()

    update_version_in_gradle(ver)
    build_apk()

    print("📤 Фиксация изменений в Git...")
    subprocess.run(["git", "add", "-A"], cwd=REPO_ROOT)
    subprocess.run(["git", "commit", "-m", f"release: publish version {tag}"], cwd=REPO_ROOT)
    subprocess.run(["git", "push", "origin", "main"], cwd=REPO_ROOT)

    title = f"2PChat {tag}"
    for owner, repo in REPOS_TO_PUBLISH:
        upload_release_to_github(owner, repo, tag, title, notes, APK_PATH, token)

    print("\n" + "=" * 60)
    print(f"✨ РЕЛИЗ {tag} УСПЕШНО ОПУБЛИКОВАН В ОБОИХ РЕПОЗИТОРИЯХ!")
    print(f"📱 Публичный релиз (для пользователей): https://github.com/kodzyfox/2pchat-releases/releases/tag/{tag}")
    print(f"💻 Основной репозиторий:               https://github.com/Nulliik/2pchat/releases/tag/{tag}")
    print("=" * 60 + "\n")

if __name__ == "__main__":
    main()
