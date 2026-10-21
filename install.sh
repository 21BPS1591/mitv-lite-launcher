#!/usr/bin/env bash
# Installs MiTV Lite Launcher, makes it the home screen and debloats the TV.
# Usage: ./install.sh <TV_IP> [path/to/apk]      (add --keep-bloat to skip debloating)
set -u
export MSYS_NO_PATHCONV=1
IP="${1:?usage: ./install.sh <TV_IP> [apk] [--keep-bloat]}"
APK="${2:-mitv-lite-launcher-v1.0.apk}"
KEEP=0; for a in "$@"; do [ "$a" = "--keep-bloat" ] && KEEP=1; done
[ "$APK" = "--keep-bloat" ] && APK="mitv-lite-launcher-v1.0.apk"
DIR="$(cd "$(dirname "$0")" && pwd)"
command -v adb >/dev/null || { echo "adb not found. Install Android platform-tools first (see GUIDE.md)."; exit 1; }
[ -f "$APK" ] || { echo "APK not found: $APK"; exit 1; }

adb connect "$IP:5555" >/dev/null
adb -s "$IP:5555" get-state >/dev/null 2>&1 || { echo "Cannot reach $IP:5555. Enable network debugging and accept the prompt on the TV."; exit 1; }
T="adb -s $IP:5555"

echo "Installing launcher..."
$T install -r "$APK" || exit 1
$T shell cmd package set-home-activity dev.lite.launcher/.MainActivity

if ! $T shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | grep -q dev.lite.launcher; then
  echo "Launcher is not the default home yet; stopping before touching anything else."; exit 1
fi

if [ "$KEEP" = 0 ]; then
  echo "Removing bloatware..."
  grep -vE '^\s*(#|$)' "$DIR/debloat/remove.txt" | tr -d '\r' | while read -r p; do
    printf '  %s: ' "$p"; $T shell pm uninstall -k --user 0 "$p" </dev/null | tr -d '\r'
  done
  echo "Disabling stock launchers..."
  grep -vE '^\s*(#|$)' "$DIR/debloat/disable.txt" | tr -d '\r' | while read -r p; do
    printf '  %s: ' "$p"; $T shell pm disable-user --user 0 "$p" </dev/null 2>&1 | head -2 | tr -d '\r'
  done
fi
echo "Done. Press Home on the remote."
