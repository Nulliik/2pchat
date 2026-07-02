$ErrorActionPreference = "Stop"

Write-Host "Installing Python dependencies..."
python -m pip install -r messenger/requirements.txt
python -m pip install pyinstaller

Write-Host "Building Windows executable with PyInstaller..."
python -m PyInstaller --noconfirm --clean --log-level INFO messenger_kivy.spec

Write-Host "Build finished. Output: dist/2PChat/"
