#!/usr/bin/env bash
# Change-source smoke: unit tests + installAppDebug (+ optional pref assert).
# Usage:
#   GRADLE_USER_HOME=E:/.gradle ./scripts/change-source-smoke.sh
#   ./scripts/change-source-smoke.sh --prefs
#   ./scripts/change-source-smoke.sh --assert-prefs
#   ./scripts/change-source-smoke.sh --unit-only
#   ./scripts/change-source-smoke.sh --install-only
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PKG="${LEGADO_DEBUG_PKG:-com.legado.app.debug}"
# Prefer existing env; else Git Bash E: drive home; else WSL /mnt/e/.gradle
if [[ -z "${GRADLE_USER_HOME:-}" ]]; then
  if [[ -d /e/.gradle ]]; then
    export GRADLE_USER_HOME=/e/.gradle
  elif [[ -d /mnt/e/.gradle ]]; then
    export GRADLE_USER_HOME=/mnt/e/.gradle
  else
    echo "Set GRADLE_USER_HOME to E:/.gradle (Windows same-drive KSP)" >&2
    exit 1
  fi
fi

MODE="all"
case "${1:-}" in
  --prefs) MODE="prefs" ;;
  --assert-prefs) MODE="assert-prefs" ;;
  --unit-only) MODE="unit" ;;
  --install-only) MODE="install" ;;
  -h|--help)
    sed -n '2,10p' "$0"
    exit 0
    ;;
esac

rg_or_grep() {
  if command -v rg >/dev/null 2>&1; then
    rg "$@"
  else
    grep -E "$@"
  fi
}

dump_prefs() {
  if ! command -v adb >/dev/null; then
    echo "adb missing" >&2
    exit 1
  fi
  local out
  if ! out="$(adb shell "run-as $PKG cat shared_prefs/${PKG}_preferences.xml" 2>/dev/null)"; then
    echo "could not read prefs for $PKG (debug install? run-as?)" >&2
    exit 1
  fi
  printf '%s\n' "$out" | rg_or_grep 'changeSource|autoChangeSource' || {
    echo "prefs XML readable but no changeSource* keys" >&2
    exit 1
  }
}

assert_prefs() {
  local xml
  xml="$(adb shell "run-as $PKG cat shared_prefs/${PKG}_preferences.xml" 2>/dev/null)" || {
    echo "could not read prefs for $PKG" >&2
    exit 1
  }
  local ok=0
  printf '%s\n' "$xml" | rg_or_grep 'name="changeSourceLoadWordCount" value="true"' >/dev/null || {
    echo "FAIL: changeSourceLoadWordCount must be true (菜单「加载字数」)" >&2
    ok=1
  }
  printf '%s\n' "$xml" | rg_or_grep 'name="changeSourceEarlyStop" value="true"' >/dev/null || {
    # key missing → code default true; explicit false fails
    if printf '%s\n' "$xml" | rg_or_grep 'name="changeSourceEarlyStop" value="false"' >/dev/null; then
      echo "FAIL: changeSourceEarlyStop is false (菜单「足够好源后提前停止」)" >&2
      ok=1
    fi
  }
  dump_prefs
  return "$ok"
}

unit() {
  ./gradlew :app:testAppDebugUnitTest \
    --tests 'io.legado.app.model.checkalgo.*' \
    --tests 'io.legado.app.model.Rfc001*'
}

install() {
  ./gradlew :app:installAppDebug
  adb shell dumpsys package "$PKG" | rg_or_grep 'versionName|versionCode|lastUpdateTime' | head -5
}

case "$MODE" in
  prefs) dump_prefs ;;
  assert-prefs) assert_prefs ;;
  unit) unit ;;
  install) install ;;
  all)
    unit
    install
    if [[ "$(adb get-state 2>/dev/null || true)" == "device" ]]; then
      echo "--- changeSource prefs (warn only; use --assert-prefs to gate) ---"
      dump_prefs || echo "WARN: prefs unread — open 换源 menu and set 加载字数 + 足够好源后提前停止" >&2
    fi
    echo "OK: unit+install done. Device UI: docs/guides/change-chapter-verify-test.md"
    ;;
esac
