#!/usr/bin/env bash
set -euo pipefail

# 2PChat Egress Isolation Test Harness (SEC-08 / G-03)
# Automates live packet capture and socket analysis on AOSP emulator.

MODE="${1:-strict}" # "strict" or "speed"
DURATION_SEC="${2:-30}"
OUTPUT_DIR="${3:-$(pwd)/egress_reports}"
PACKAGE_NAME="com.example.twopchat.go"
CONTROL_PORT=9051

mkdir -p "$OUTPUT_DIR"

echo "=== Starting 2PChat Egress Test Harness (Mode: ${MODE^^}) ==="

# 1. Verify ADB connection
if ! command -v adb >/dev/null 2>&1; then
    echo "ERROR: adb command not found in PATH." >&2
    exit 1
fi

DEVICE_COUNT=$(adb devices | grep -v "List" | grep "device" | wc -l | tr -d ' ')
if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "ERROR: No Android device or emulator detected via adb." >&2
    exit 1
fi

echo "[1/6] Enabling root on adb daemon..."
adb root || true
sleep 1

# 2. Identify App UID
echo "[2/6] Detecting UID for $PACKAGE_NAME..."
APP_UID=$(adb shell "id -u $PACKAGE_NAME 2>/dev/null" | tr -d '\r\n' || true)
if [ -z "$APP_UID" ]; then
    APP_UID=$(adb shell "pm list packages -U | grep $PACKAGE_NAME" | awk -F'uid:' '{print $2}' | tr -d '\r\n' || true)
fi
echo "App UID: ${APP_UID:-unknown}"

# 3. Dump Tor OR-connection status (Guard allowlist)
ORCONN_LOG="$OUTPUT_DIR/orconn-${MODE}.txt"
echo "[3/6] Querying Tor Guard connections via control port $CONTROL_PORT..."
adb shell "echo 'GETINFO orconn-status' | nc 127.0.0.1 $CONTROL_PORT" > "$ORCONN_LOG" 2>/dev/null || true

# 4. Start tcpdump & socket monitor
PCAP_REMOTE="/sdcard/egress-${MODE}.pcap"
PCAP_LOCAL="$OUTPUT_DIR/egress-${MODE}.pcap"
SOCKETS_LOCAL="$OUTPUT_DIR/sockets-${MODE}.log"

echo "[4/6] Starting full interface packet capture ($PCAP_REMOTE)..."
adb shell "rm -f $PCAP_REMOTE"
adb shell "tcpdump -i any -nn -U -w $PCAP_REMOTE" >/dev/null 2>&1 &
TCPDUMP_PID=$!

echo "[5/6] Monitoring process sockets for ${DURATION_SEC}s..."
MONITOR_END=$((SECONDS + DURATION_SEC))
> "$SOCKETS_LOCAL"
while [ $SECONDS -lt $MONITOR_END ]; do
    if [ -n "$APP_UID" ]; then
        adb shell "ss -tunapH 2>/dev/null | grep 'uid:$APP_UID'" >> "$SOCKETS_LOCAL" || true
    fi
    sleep 0.5
done

# Stop tcpdump
echo "Stopping capture..."
adb shell "pkill -2 tcpdump" || true
sleep 2

# 5. Pull artifacts
echo "[6/6] Pulling artifacts..."
adb pull "$PCAP_REMOTE" "$PCAP_LOCAL" 2>/dev/null || true

# 6. Run PCAP Analyzer
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
python3 "$SCRIPT_DIR/analyze_egress_pcap.py" --pcap "$PCAP_LOCAL" --mode "$MODE" --orconn "$ORCONN_LOG"
echo "=== Harness execution complete ==="
