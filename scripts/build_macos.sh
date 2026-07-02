#!/usr/bin/env bash
set -euo pipefail

python -m pip install -r messenger/requirements.txt
python -m pip install pyinstaller

python -m PyInstaller --noconfirm --clean messenger_kivy.spec

echo "Build finished. Output: dist/2PChat.app"

echo "Optional signing/notarization commands:"
echo "  codesign --deep --force --verify --verbose --sign \"Developer ID Application: YOUR TEAM\" dist/2PChat.app"
echo "  ditto -c -k --keepParent dist/2PChat.app dist/2PChat-macos.zip"
echo "  xcrun notarytool submit dist/2PChat-macos.zip --apple-id ... --team-id ... --password ... --wait"
echo "  xcrun stapler staple dist/2PChat.app"
