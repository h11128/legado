#!/usr/bin/env bash
# Automate one 整书换源 device session and capture LegadoChangeSource logcat.
#
# Usage (Git Bash / WSL):
#   GRADLE_USER_HOME=E:/.gradle ./scripts/change-source-device-session.sh
#   ./scripts/change-source-device-session.sh --book-url 'http://…'
#   ./scripts/change-source-device-session.sh --no-install
#   ./scripts/change-source-device-session.sh --timeout-s 180
#
# Flow: prefs → (optional install) → open ReadBook → show menu → tap 换源 →
# tap 刷新 once → wait finish/early-stop → analyze log → write report under temp/.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export MSYS_NO_PATHCONV=1
export PYTHONIOENCODING=utf-8

PKG="${LEGADO_DEBUG_PKG:-com.legado.app.debug}"
TIMEOUT_S=180
DO_INSTALL=1
BOOK_URL="${CHANGE_SOURCE_BOOK_URL:-}"
STAMP="$(date +%Y-%m-%d_%H%M%S)"
# Relative paths (from repo root) — Windows Python + adb handle these; absolute /e/... does not.
OUT_REL="temp"
LOG="${OUT_REL}/legado_change_source_session_${STAMP}.txt"
REPORT_MD="${OUT_REL}/change_source_analyze_${STAMP}.md"
REPORT_JSON="${OUT_REL}/change_source_analyze_${STAMP}.json"
SQLITE="${SQLITE3:-}"
if [[ -z "$SQLITE" ]]; then
  if command -v sqlite3 >/dev/null 2>&1; then
    SQLITE="$(command -v sqlite3)"
  elif [[ -n "${ANDROID_HOME:-}" && -x "${ANDROID_HOME}/platform-tools/sqlite3" ]]; then
    SQLITE="${ANDROID_HOME}/platform-tools/sqlite3"
  elif [[ -n "${ANDROID_SDK_ROOT:-}" && -x "${ANDROID_SDK_ROOT}/platform-tools/sqlite3" ]]; then
    SQLITE="${ANDROID_SDK_ROOT}/platform-tools/sqlite3"
  fi
fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-install) DO_INSTALL=0; shift ;;
    --timeout-s) TIMEOUT_S="$2"; shift 2 ;;
    --book-url) BOOK_URL="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,14p' "$0"
      exit 0
      ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "${GRADLE_USER_HOME:-}" ]]; then
  if [[ -d /e/.gradle ]]; then export GRADLE_USER_HOME=/e/.gradle
  elif [[ -d /mnt/e/.gradle ]]; then export GRADLE_USER_HOME=/mnt/e/.gradle
  fi
fi
# Avoid trailing space pollution breaking wrapper lock paths.
GRADLE_USER_HOME="$(printf '%s' "${GRADLE_USER_HOME}" | tr -d '\r' | sed 's/[[:space:]]*$//')"
export GRADLE_USER_HOME

need_adb() {
  command -v adb >/dev/null || { echo "adb missing" >&2; exit 1; }
  [[ "$(adb get-state 2>/dev/null || true)" == "device" ]] || {
    echo "no adb device" >&2
    exit 1
  }
}

resolve_book_url() {
  if [[ -n "$BOOK_URL" ]]; then
    echo "$BOOK_URL"
    return
  fi
  mkdir -p "$OUT_REL"
  adb exec-out "run-as $PKG cat databases/legado.db" > "${OUT_REL}/_cs_books.db"
  adb exec-out "run-as $PKG cat databases/legado.db-wal" > "${OUT_REL}/_cs_books.db-wal" 2>/dev/null || true
  adb exec-out "run-as $PKG cat databases/legado.db-shm" > "${OUT_REL}/_cs_books.db-shm" 2>/dev/null || true
  if [[ -z "$SQLITE" ]]; then
    echo "sqlite3 missing; pass --book-url" >&2
    exit 1
  fi
  "$SQLITE" "${OUT_REL}/_cs_books.db" "PRAGMA wal_checkpoint(FULL);" >/dev/null
  local url
  url="$("$SQLITE" "${OUT_REL}/_cs_books.db" \
    "SELECT bookUrl FROM books ORDER BY durChapterTime DESC LIMIT 1;")"
  [[ -n "$url" ]] || { echo "no bookshelf book" >&2; exit 1; }
  echo "$url"
}

ui_dump() {
  local dest="$1"
  adb shell uiautomator dump /sdcard/_cs_ui.xml >/dev/null
  adb pull /sdcard/_cs_ui.xml "$dest" >/dev/null
}

# Prints: STATE X Y   STATE is refresh|stop|other|missing (ASCII only)
find_start_stop() {
  local xml="$1"
  python - "$xml" <<'PY'
import re, sys
from pathlib import Path
t = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
m = re.search(
    r'resource-id="[^"]*menu_start_stop"[^>]*content-desc="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
    t,
)
if not m:
    m = re.search(
        r'resource-id="[^"]*menu_start_stop"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*content-desc="([^"]*)"',
        t,
    )
    if m:
        x = (int(m.group(1)) + int(m.group(3))) // 2
        y = (int(m.group(2)) + int(m.group(4))) // 2
        desc = m.group(5)
    else:
        print("missing 0 0")
        raise SystemExit(0)
else:
    desc = m.group(1)
    x = (int(m.group(2)) + int(m.group(4))) // 2
    y = (int(m.group(3)) + int(m.group(5))) // 2
if desc in ("刷新", "Refresh", "开始", "Start"):
    state = "refresh"
elif desc in ("停止", "Stop"):
    state = "stop"
else:
    state = "other"
print(f"{state} {x} {y}")
PY
}

find_change_source_btn() {
  local xml="$1"
  python - "$xml" <<'PY'
import re, sys
from pathlib import Path
t = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
m = re.search(
    r'content-desc="换源"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
    t,
)
if not m:
    print("0 0")
    raise SystemExit(0)
x = (int(m.group(1)) + int(m.group(3))) // 2
y = (int(m.group(2)) + int(m.group(4))) // 2
print(f"{x} {y}")
PY
}

need_adb
mkdir -p "$OUT_REL"

echo "== prefs =="
./scripts/change-source-smoke.sh --apply-prefs

if [[ "$DO_INSTALL" == "1" ]]; then
  echo "== install =="
  ./scripts/change-source-smoke.sh --install-only
fi

BOOK_URL="$(resolve_book_url)"
echo "bookUrl=$BOOK_URL"

echo "== open ReadBook =="
adb shell am force-stop "$PKG" || true
sleep 1
adb shell am start -n "$PKG/io.legado.app.ui.book.read.ReadBookActivity" \
  --es bookUrl "$BOOK_URL"
sleep 4

ui_dump "${OUT_REL}/_cs_ui_read.xml"
CS_XY="$(find_change_source_btn "${OUT_REL}/_cs_ui_read.xml")"
if [[ "$CS_XY" == "0 0" ]]; then
  adb shell input tap 540 1200
  sleep 1
  ui_dump "${OUT_REL}/_cs_ui_read.xml"
  CS_XY="$(find_change_source_btn "${OUT_REL}/_cs_ui_read.xml")"
fi
[[ "$CS_XY" != "0 0" ]] || { echo "换源 button not found" >&2; exit 1; }
echo "tap 换源 $CS_XY"
adb shell input tap $CS_XY
sleep 2

ui_dump "${OUT_REL}/_cs_ui_dialog.xml"
BTN="$(find_start_stop "${OUT_REL}/_cs_ui_dialog.xml")"
echo "start/stop btn: $BTN"
STATE="$(echo "$BTN" | awk '{print $1}')"
X="$(echo "$BTN" | awk '{print $2}')"
Y="$(echo "$BTN" | awk '{print $3}')"
[[ "$STATE" != "missing" && "$X" != "0" ]] || { echo "menu_start_stop missing" >&2; exit 1; }

# Dialog may auto-start search on open. Drain to idle (refresh) before capturing.
for _ in $(seq 1 8); do
  if [[ "$STATE" != "stop" ]]; then
    break
  fi
  echo "stopping leftover search ($STATE $X $Y)"
  adb shell input tap "$X" "$Y"
  sleep 2
  ui_dump "${OUT_REL}/_cs_ui_dialog.xml"
  BTN="$(find_start_stop "${OUT_REL}/_cs_ui_dialog.xml")"
  STATE="$(echo "$BTN" | awk '{print $1}')"
  X="$(echo "$BTN" | awk '{print $2}')"
  Y="$(echo "$BTN" | awk '{print $3}')"
  echo "after stop: $BTN"
done
if [[ "$STATE" == "stop" ]]; then
  echo "WARN: still showing stop after drain; waiting 3s more" >&2
  sleep 3
  ui_dump "${OUT_REL}/_cs_ui_dialog.xml"
  BTN="$(find_start_stop "${OUT_REL}/_cs_ui_dialog.xml")"
  STATE="$(echo "$BTN" | awk '{print $1}')"
  X="$(echo "$BTN" | awk '{print $2}')"
  Y="$(echo "$BTN" | awk '{print $3}')"
fi
[[ "$STATE" == "refresh" || "$STATE" == "other" ]] || {
  echo "could not reach idle start button (state=$STATE)" >&2
  exit 1
}

adb logcat -c
rm -f "$LOG"
adb logcat -v time -s LegadoChangeSource:I > "$LOG" &
LOGPID=$!
cleanup() { kill "$LOGPID" 2>/dev/null || true; }
trap cleanup EXIT

echo "start search once ($STATE $X $Y)"
adb shell input tap "$X" "$Y"

START_SEEN=0
for _ in $(seq 1 40); do
  sleep 0.25
  if grep -q " start " "$LOG" 2>/dev/null; then
    echo "log start seen"
    START_SEEN=1
    break
  fi
done
if [[ "$START_SEEN" != "1" ]]; then
  echo "FAIL: no LegadoChangeSource start line after tap" >&2
  cleanup
  trap - EXIT
  exit 1
fi

DEADLINE=$((SECONDS + TIMEOUT_S))
while (( SECONDS < DEADLINE )); do
  if grep -q " finish " "$LOG" 2>/dev/null; then
    echo "log finish seen"
    break
  fi
  sleep 2
done
sleep 2
cleanup
trap - EXIT

echo "== analyze =="
set +e
python scripts/change-source-analyze-log.py "$LOG" \
  --out "$REPORT_MD" --json "$REPORT_JSON" --expect-deep-cap
ANALYZE_RC=$?
set -e

echo "LOG=$LOG"
echo "REPORT_MD=$REPORT_MD"
echo "REPORT_JSON=$REPORT_JSON"
exit "$ANALYZE_RC"
