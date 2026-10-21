#!/usr/bin/env bash
# Undoes install.sh: brings back removed apps, re-enables stock launchers.
# Usage: ./restore.sh <TV_IP>
set -u
export MSYS_NO_PATHCONV=1
IP="${1:?usage: ./restore.sh <TV_IP>}"
DIR="$(cd "$(dirname "$0")" && pwd)"
adb connect "$IP:5555" >/dev/null
T="adb -s $IP:5555"
grep -vE '^\s*(#|$)' "$DIR/debloat/disable.txt" | tr -d '\r' | while read -r p; do
  printf '  enable %s: ' "$p"; $T shell pm enable "$p" </dev/null 2>&1 | head -2 | tr -d '\r'
done
grep -vE '^\s*(#|$)' "$DIR/debloat/remove.txt" | tr -d '\r' | while read -r p; do
  printf '  restore %s: ' "$p"; $T shell cmd package install-existing "$p" </dev/null 2>&1 | head -2 | tr -d '\r'
done
$T shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity >/dev/null 2>&1
echo "Restored. Optionally uninstall the launcher: adb -s $IP:5555 uninstall dev.lite.launcher"
