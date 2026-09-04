#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ -d "$ROOT_DIR/app/src/main" ]; then
    TARGET_DIR="$ROOT_DIR/app/src/main"
elif [ -d "$ROOT_DIR/android/app/src/main" ]; then
    TARGET_DIR="$ROOT_DIR/android/app/src/main"
elif [ -d "$ROOT_DIR/2pchatGO/android/app/src/main" ]; then
    TARGET_DIR="$ROOT_DIR/2pchatGO/android/app/src/main"
else
    echo "❌ Cannot find app/src/main directory"
    exit 1
fi

BAD=$(grep -rn "^import android.util.Log$" "$TARGET_DIR" --include="*.kt" \
      | grep -v "/logging/SafeLog.kt" | grep -v "/logging/AppLog.kt" || true)
BAD2=$(grep -rn "android\.util\.Log\.[vdiwe](" "$TARGET_DIR" --include="*.kt" \
      | grep -v "/logging/" || true)

if [ -n "$BAD$BAD2" ]; then
  echo "❌ Raw android.util.Log usage found (use SafeLog):"
  [ -n "$BAD" ] && echo "$BAD"
  [ -n "$BAD2" ] && echo "$BAD2"
  exit 1
fi

echo "✅ no raw android.util.Log usage"
