#!/usr/bin/env bash
# Device session: open a dead/empty-toc shelf book and capture auto-换源 logcat.
#
# Usage (Git Bash / WSL):
#   GRADLE_USER_HOME=E:/.gradle ./scripts/auto-change-device-session.sh
#   ./scripts/auto-change-device-session.sh --no-install
#   ./scripts/auto-change-device-session.sh --book-url 'http://…'
#   ./scripts/auto-change-device-session.sh --kind missing_source --timeout-s 90
#
# Picks candidates via scripts/auto-change-pick-book.py (missing_source preferred).
# Writes temp/legado_auto_change_session_<stamp>.txt + analyze json/md summary.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
export MSYS_NO_PATHCONV=1
export PYTHONIOENCODING=utf-8

PKG="${LEGADO_DEBUG_PKG:-com.legado.app.debug}"
TIMEOUT_S=90
DO_INSTALL=1
BOOK_URL="${AUTO_CHANGE_BOOK_URL:-}"
KIND="all"
STAMP="$(date +%Y-%m-%d_%H%M%S)"
OUT_REL="temp"
LOG="${OUT_REL}/legado_auto_change_session_${STAMP}.txt"
REPORT_JSON="${OUT_REL}/auto_change_analyze_${STAMP}.json"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-install) DO_INSTALL=0; shift ;;
    --timeout-s) TIMEOUT_S="$2"; shift 2 ;;
    --book-url) BOOK_URL="$2"; shift 2 ;;
    --kind) KIND="$2"; shift 2 ;;
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
GRADLE_USER_HOME="$(printf '%s' "${GRADLE_USER_HOME}" | tr -d '\r' | sed 's/[[:space:]]*$//')"
export GRADLE_USER_HOME

need_adb() {
  command -v adb >/dev/null || { echo "adb missing" >&2; exit 1; }
  [[ "$(adb get-state 2>/dev/null || true)" == "device" ]] || {
    echo "no adb device" >&2
    exit 1
  }
}

need_adb
mkdir -p "$OUT_REL"

echo "== prefs (ensure autoChangeSource) =="
./scripts/change-source-smoke.sh --apply-prefs || true

if [[ "$DO_INSTALL" == "1" ]]; then
  echo "== install =="
  ./scripts/change-source-smoke.sh --install-only
fi

if [[ -z "$BOOK_URL" ]]; then
  echo "== pick candidate (--kind $KIND) =="
  python scripts/auto-change-pick-book.py --kind "$KIND" --limit 8 || true
  BOOK_URL="$(python scripts/auto-change-pick-book.py --kind "$KIND" --pick)"
fi
echo "bookUrl=$BOOK_URL"

echo "== open ReadBook =="
adb logcat -c || true
adb shell am force-stop "$PKG" || true
sleep 1
# Quote for shell metacharacters in bookUrl
python - "$PKG" "$BOOK_URL" <<'PY'
import subprocess, sys, time
pkg, url = sys.argv[1], sys.argv[2]
subprocess.check_call(
    ["adb", "shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"],
    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
)
time.sleep(3)
esc = url.replace("'", "'\\''")
cmd = (
    f"am start -n {pkg}/io.legado.app.ui.book.read.ReadBookActivity "
    f"--es bookUrl '{esc}' --ez inBookshelf true"
)
print(subprocess.check_output(["adb", "shell", cmd], text=True, errors="ignore"))
PY

echo "== capture LegadoChangeSource (${TIMEOUT_S}s) =="
# timeout(1) may be missing on Git Bash — use python waiter
python - "$LOG" "$TIMEOUT_S" <<'PY'
import subprocess, sys, time
from pathlib import Path
log_path = Path(sys.argv[1])
timeout_s = int(sys.argv[2])
proc = subprocess.Popen(
    ["adb", "logcat", "-v", "time", "LegadoChangeSource:I", "*:S"],
    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, errors="ignore",
)
lines = []
t0 = time.time()
saw_ask = False
extra_until = None
assert proc.stdout
while time.time() - t0 < timeout_s:
    line = proc.stdout.readline()
    if not line:
        time.sleep(0.05)
        continue
    lines.append(line)
    print(line.rstrip())
    low = line.lower()
    if "auto-change trigger=" in low:
        extra_until = time.time() + 45
    if "auto-change ask" in low:
        saw_ask = True
        extra_until = max(extra_until or 0, time.time() + 30)
    if saw_ask and extra_until and time.time() > extra_until:
        break
proc.kill()
log_path.write_text("".join(lines), encoding="utf-8")
print(f"wrote {log_path} lines={len(lines)}")
PY

echo "== analyze =="
python scripts/auto-change-analyze-log.py "$LOG" --json | tee "$REPORT_JSON"
python scripts/auto-change-analyze-log.py "$LOG"
echo "report=$REPORT_JSON"
echo "log=$LOG"
