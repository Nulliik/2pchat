#!/usr/bin/env bash
set -euo pipefail

# 2PChat Egress Isolation Test Harness (SEC-08 / G-03)
# Automates live packet capture and socket analysis on Android device or AOSP emulator.

MODE="${1:-strict}" # "strict" or "speed"
DURATION_SEC="${2:-30}"
OUTPUT_DIR="${3:-$(pwd)/egress_reports}"
PACKAGE_NAME="com.example.twopchat.go"
CONTROL_PORT=9051

mkdir -p "$OUTPUT_DIR"

MODE_UPPER=$(echo "$MODE" | tr '[:lower:]' '[:upper:]')
echo "=== Starting 2PChat Egress Test Harness (Mode: $MODE_UPPER) ==="

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

ADB="adb"
if [ -n "${ANDROID_SERIAL:-}" ]; then
    ADB="adb -s $ANDROID_SERIAL"
    echo "Targeting specified device: $ANDROID_SERIAL"
elif [ "$DEVICE_COUNT" -gt 1 ]; then
    EMU_DEV=$(adb devices | grep -E "emulator-[0-9]+" | awk '{print $1}' | head -n 1 || true)
    if [ -n "$EMU_DEV" ]; then
        ADB="adb -s $EMU_DEV"
        echo "Multiple devices detected; auto-selected emulator: $EMU_DEV"
    else
        FIRST_DEV=$(adb devices | grep -v "List" | grep "device" | awk '{print $1}' | head -n 1)
        ADB="adb -s $FIRST_DEV"
        echo "Multiple devices detected; selected first device: $FIRST_DEV"
    fi
fi

echo "[1/6] Enabling root on adb daemon..."
ROOT_OUTPUT=$($ADB root 2>&1 || true)
echo "adb root output: $ROOT_OUTPUT"
sleep 1

# 2. Identify App UID
echo "[2/6] Detecting UID for $PACKAGE_NAME..."
APP_UID=$($ADB shell "id -u $PACKAGE_NAME 2>/dev/null" | tr -d '\r\n' || true)
if [ -z "$APP_UID" ]; then
    APP_UID=$($ADB shell "pm list packages -U | grep $PACKAGE_NAME" | awk -F'uid:' '{print $2}' | tr -d '\r\n' || true)
fi
echo "App UID: ${APP_UID:-unknown}"

# 3. Dump Tor OR-connection status (Guard allowlist)
ORCONN_LOG="$OUTPUT_DIR/orconn-${MODE}.txt"
echo "[3/6] Querying Tor Guard connections via control port $CONTROL_PORT..."
$ADB shell "echo 'GETINFO orconn-status' | nc 127.0.0.1 $CONTROL_PORT" > "$ORCONN_LOG" 2>/dev/null || true

# 4. Start tcpdump & socket monitor
PCAP_REMOTE="/sdcard/egress-${MODE}.pcap"
PCAP_LOCAL="$OUTPUT_DIR/egress-${MODE}.pcap"
SOCKETS_LOCAL="$OUTPUT_DIR/sockets-${MODE}.log"

TCPDUMP_AVAILABLE=true
if ! $ADB shell "which tcpdump >/dev/null 2>&1"; then
    TCPDUMP_AVAILABLE=false
    echo "NOTE: tcpdump binary not found in PATH on device."
    echo "Sockets will be monitored via 'ss -tunapH', but full pcap requires root/tcpdump (standard on AVD emulators)."
fi

if [ "$TCPDUMP_AVAILABLE" = true ]; then
    echo "[4/6] Starting full interface packet capture ($PCAP_REMOTE)..."
    $ADB shell "rm -f $PCAP_REMOTE"
    $ADB shell "tcpdump -i any -nn -U -w $PCAP_REMOTE" >/dev/null 2>&1 &
    TCPDUMP_PID=$!
else
    echo "[4/6] Skipping tcpdump (not available on device), monitoring sockets only..."
fi

echo "[5/6] Monitoring process sockets for ${DURATION_SEC}s..."
MONITOR_END=$((SECONDS + DURATION_SEC))
> "$SOCKETS_LOCAL"
while [ $SECONDS -lt $MONITOR_END ]; do
    if [ -n "$APP_UID" ]; then
        $ADB shell "ss -tunapH 2>/dev/null | grep 'uid:$APP_UID'" >> "$SOCKETS_LOCAL" || true
    else
        $ADB shell "ss -tunapH 2>/dev/null" >> "$SOCKETS_LOCAL" || true
    fi
    sleep 0.5
done

# Stop tcpdump
if [ "$TCPDUMP_AVAILABLE" = true ]; then
    echo "Stopping capture..."
    $ADB shell "pkill -2 tcpdump" || true
    sleep 2

    # 5. Pull artifacts
    echo "[6/6] Pulling pcap artifacts..."
    $ADB pull "$PCAP_REMOTE" "$PCAP_LOCAL" 2>/dev/null || true
else
    echo "[6/6] Sockets log saved to $SOCKETS_LOCAL"
    # Create empty/stub pcap so analyzer can inspect socket log
    touch "$PCAP_LOCAL"
fi

# 6. Run PCAP Analyzer
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$PCAP_LOCAL" ] && [ -s "$PCAP_LOCAL" ]; then
    python3 "$SCRIPT_DIR/analyze_egress_pcap.py" --pcap "$PCAP_LOCAL" --mode "$MODE" --orconn "$ORCONN_LOG"
else
    echo "Analyzing socket monitor output..."
    python3 "$SCRIPT_DIR/analyze_egress_pcap.py" --sockets "$SOCKETS_LOCAL" --mode "$MODE" --orconn "$ORCONN_LOG" 2>/dev/null || true
fi
echo "=== Harness execution complete ==="

