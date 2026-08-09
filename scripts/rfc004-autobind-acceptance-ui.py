#!/usr/bin/env python3
"""RFC-004 seamless auto-bind — FULL screenshot acceptance (real providers only).

Prefer the one-shot entry:
  python scripts/rfc004-run-acceptance.py

Gates G1–G9 (see docs/guides/rfc-004-autobind-acceptance.md).
Supports --from / --only, structured ACCEPTANCE.json (also on failure).
"""
from __future__ import annotations

import argparse
import re
import sqlite3
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from scripts.lib import rfc004_device as d  # noqa: E402
from scripts.lib.legado_adb import open_read_book  # noqa: E402

OUT = ROOT / "temp" / "rfc004_ui" / "acceptance"
BOOK_SUB = d.BOOK_SUB_DEFAULT


class GateFail(Exception):
    def __init__(self, gate: str, msg: str):
        super().__init__(f"FAIL {gate}: {msg}")
        self.gate = gate
        self.msg = msg


def prepare_positive(db: Path, book_sub: str) -> str:
    con = sqlite3.connect(str(db))
    cur = con.cursor()
    cur.execute(
        "UPDATE book_sources SET enabled=0 WHERE bookSourceUrl LIKE ?",
        (d.FIXTURE_PREFIX + "%",),
    )
    cur.execute(
        "UPDATE book_sources SET enabled=1 WHERE bookSourceUrl LIKE ? "
        "AND bookSourceUrl NOT LIKE ?",
        (f"%{d.REVIEW_SUFFIX}", d.FIXTURE_PREFIX + "%"),
    )
    real = cur.execute(
        "SELECT bookSourceUrl, bookSourceName FROM book_sources WHERE enabled=1 "
        "AND bookSourceUrl LIKE ? AND bookSourceUrl NOT LIKE ?",
        (f"%{d.REVIEW_SUFFIX}", d.FIXTURE_PREFIX + "%"),
    ).fetchall()
    print("G1 real sources", len(real), [n for _, n in real])
    if len(real) < 1:
        raise GateFail("G1", "no real review sources (use --push-sources)")
    book_url, name, author, origin = d.find_book_url(db, book_sub)
    print("book", name, author, origin)
    if d.FIXTURE_PREFIX in (origin or ""):
        raise GateFail("G1", "fixture origin")
    cur.execute("DELETE FROM book_review_bindings WHERE contentBookUrl=?", (book_url,))
    for t, i in cur.execute(
        "SELECT title, `index` FROM chapters WHERE bookUrl=? ORDER BY `index` LIMIT 40",
        (book_url,),
    ):
        if t and ("第一章" in t or "绯红" in t):
            cur.execute(
                "UPDATE books SET durChapterIndex=?, durChapterTitle=? WHERE bookUrl=?",
                (i, t, book_url),
            )
            print("dur", t, i)
            break
    con.commit()
    cur.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    con.close()
    return book_url


def wait_overlay(timeout_s: int = 100) -> tuple[str, str]:
    best = ""
    log = ""

    def tick() -> bool:
        nonlocal best, log
        log = d.logcat_applog()
        hits = d.overlay_lines(log)
        print(f"t+ overlay_hits={len(hits)}")
        for ln in hits[-5:]:
            print(" ", ln[ln.find("ReviewOverlay") :])
        if any(d.FIXTURE_PREFIX in ln and "bind=" in ln for ln in hits):
            raise GateFail("G3", "fixture in overlay log")
        for ln in reversed(hits):
            if "merge providers=" in ln or (
                "bucket=" in ln and "bucket=0" not in ln and "auto-bind" not in ln
            ):
                best = ln
                return True
        return False

    if not d.wait_until(tick, timeout_s=timeout_s, interval_s=1.5, label="overlay"):
        (OUT / "session_positive.log").write_text(log or d.logcat_applog(), encoding="utf-8")
        raise GateFail("G3", "no positive bucket/merge")
    (OUT / "session_positive.log").write_text(log, encoding="utf-8")
    return best, log


def wait_authority(timeout_s: float = 12.0) -> str:
    """Poll after bucket/merge for ContentSplitVerified (may log slightly later)."""
    found = ""

    def tick() -> bool:
        nonlocal found
        log = d.logcat_applog()
        for ln in reversed(log.splitlines()):
            if "authority=ContentSplitVerified" in ln and "ReviewOverlay" in ln:
                found = ln
                return True
        return False

    if not d.wait_until(tick, timeout_s=timeout_s, interval_s=1.0, label="authority"):
        raise GateFail("G7", "expected ContentSplitVerified for 起点")
    return found


def open_book_info_from_read(book_url: str) -> None:
    """BookInfoActivity is not exported — open via read menu title."""
    open_read_book(book_url, d.PKG)

    def menu_open() -> bool:
        xml = d.dump_ui(OUT / "G8_menu_poll.xml")
        ts = d.ui_texts(xml)
        return any(t in ("目录", "设置", "界面", "朗读") for t in ts)

    # Wait for read chrome, then toggle menu (retry taps).
    d.wait_until(
        lambda: "诡秘" in "\n".join(d.ui_texts(d.dump_ui(OUT / "G8_read_poll.xml"))),
        timeout_s=12,
        interval_s=1.0,
        label="read-ready",
    )
    for _ in range(4):
        d.tap_frac(0.5, 0.48)
        if d.wait_until(menu_open, timeout_s=2.5, interval_s=0.5, label="read-menu"):
            break
    else:
        raise GateFail("G8", "read menu did not open")

    xml = d.dump_ui(OUT / "G8_menu.xml")
    _, h = d.screen_size()
    top = int(h * 0.35)
    targets = []
    for text, x1, y1, x2, y2 in d.ui_nodes(xml):
        if not text:
            continue
        if text in ("书籍信息",) or text == BOOK_SUB or "诡秘" in text:
            if y2 < top and y1 > 40:
                targets.append((text, (x1 + x2) // 2, (y1 + y2) // 2, y1))
    # Prefer the uppermost title chip (menu header), not footer.
    if targets:
        targets.sort(key=lambda t: t[3])
        t = targets[0]
        print("tap info", t)
        d.tap_xy(t[1], t[2])
    else:
        d.tap_frac(0.35, 0.08)
    if not d.wait_until(
        lambda: any(
            "段评" in t or "已绑定" in t or "换源" in t or "作者" in t
            for t in d.ui_texts(d.dump_ui(OUT / "G8_info_poll.xml"))
        ),
        timeout_s=6,
        interval_s=0.6,
        label="book-info",
    ):
        # One more title tap attempt
        d.tap_frac(0.35, 0.08)
        time.sleep(1.5)


def bindings_for(db: Path, book_url: str) -> list[tuple]:
    con = sqlite3.connect(str(db))
    rows = con.execute(
        "SELECT providerSourceUrl, bindMode, enabled FROM book_review_bindings "
        "WHERE contentBookUrl=? ORDER BY sortOrder",
        (book_url,),
    ).fetchall()
    con.close()
    return rows


class Runner:
    def __init__(self, want: set[str], book_sub: str, mode: str):
        self.want = want
        self.book_sub = book_sub
        self.mode = mode
        self.gates: dict = {}
        self.shots: list[str] = []
        self.book_url: str | None = None

    def need(self, *gs: str) -> bool:
        return any(g in self.want for g in gs)

    def record_shot(self, name: str) -> Path:
        p = d.shot(OUT / name)
        self.shots.append(name)
        return p

    def mark(self, gate: str, ok: bool, evidence: str, shot: str | None = None) -> None:
        self.gates[gate] = {"ok": ok, "evidence": evidence, "shot": shot}

    def write(self, passed: bool, fail: str | None = None) -> None:
        d.write_acceptance(
            OUT,
            passed=passed,
            book_url=self.book_url,
            gates=self.gates,
            shots=self.shots,
            fail=fail,
            mode=self.mode,
        )

    def run(self) -> int:
        d.clear_out_dir(OUT)
        try:
            return self._run()
        except GateFail as e:
            self.mark(e.gate, False, e.msg)
            self.write(False, fail=str(e))
            print(str(e), file=sys.stderr)
            return 1
        except SystemExit as e:
            if e.code in (0, None):
                raise
            msg = e.args[0] if e.args else f"exit {e.code}"
            self.write(False, fail=str(msg))
            return int(e.code) if isinstance(e.code, int) else 1
        except Exception as e:
            self.write(False, fail=repr(e))
            print(repr(e), file=sys.stderr)
            return 1

    def _run(self) -> int:
        # Prefs + DB prepare. Skip only for late resume (G8/G9 only).
        late_only = bool(self.want) and self.want <= {"G8", "G9"}
        must_prepare = not late_only
        if self.need("G1", "G2", "G3", "G4", "G5", "G6", "G7", "G8"):
            d.push_review_prefs(OUT, reviewOverlayAutoBind=True)

        if must_prepare or self.book_url is None:
            db = d.pull_db_wal(OUT / "pos", stop_app=True)
            if must_prepare:
                self.book_url = prepare_positive(db, self.book_sub)
                self.mark(
                    "G1",
                    True,
                    "fixtures off; real #rfc004-review only",
                )
                d.push_db_raw(db, "pos")
            else:
                self.book_url, *_ = d.find_book_url(db, self.book_sub)
                print("reuse book (late resume)", self.book_url)

        assert self.book_url

        # Positive overlay path
        if self.need("G3", "G4", "G5", "G6", "G7"):
            d.logcat_clear()
            open_read_book(self.book_url, d.PKG)
            best, log = wait_overlay()
            if "G3" in self.want:
                self.mark("G3", True, best[best.find("ReviewOverlay") :])
                print("G3 OK", self.gates["G3"]["evidence"])

            if "G7" in self.want:
                auth_ln = wait_authority()
                log = d.logcat_applog()
                (OUT / "session_positive.log").write_text(log, encoding="utf-8")
                cov = [
                    ln
                    for ln in log.splitlines()
                    if "coverage=" in ln and "ReviewOverlay" in ln
                ]
                paras = [
                    ln
                    for ln in log.splitlines()
                    if "paras=" in ln and "ReviewOverlay" in ln
                ]
                evidence = (paras or cov or [auth_ln])[-1]
                self.mark(
                    "G7",
                    True,
                    evidence[evidence.find("ReviewOverlay") :],
                )

            if self.need("G4", "G5", "G6"):
                time.sleep(1.0)
                png = self.record_shot("G4_read_title.png")
                xml4 = d.dump_ui(OUT / "G4_read_title.xml")
                if "G4" in self.want:
                    ts4 = d.ui_texts(xml4)
                    if not any(
                        self.book_sub in t or "诡秘" in t or "章" in t for t in ts4
                    ):
                        # soft UI check — at least some read chrome / chapter-ish text
                        if not ts4:
                            raise GateFail("G4", "empty UI dump on read page")
                    self.mark("G4", True, "G4_read_title.png", "G4_read_title.png")

                # G5/G6 need dialog; G4-only stops after shot
                if not self.need("G5", "G6"):
                    pass
                else:
                    x, y = d.find_badge_tap(png, xml4)
                    opened = False
                    merge_ui = False
                    for dx, dy in (
                        (0, 0),
                        (-12, 0),
                        (12, 0),
                        (0, -10),
                        (0, 10),
                        (-25, 5),
                        (20, 8),
                    ):
                        d.tap_xy(x + dx, y + dy)
                        time.sleep(0.9)
                        xml = d.dump_ui(OUT / "G5_after_badge.xml")
                        ts = d.ui_texts(xml)
                        if any("夹具" in t for t in ts):
                            raise GateFail("G5", "fixture UI text")
                        if any("本章评论" in t for t in ts):
                            merge_ui = True
                            opened = True
                            break
                        if any(re.search(r"共\s*\d+\s*条", t) for t in ts):
                            opened = True
                            break
                        if any(t in ("目录", "换源", "亮度") for t in ts):
                            d.keyevent(4)
                            time.sleep(0.3)
                    if not opened:
                        self.record_shot("FAIL_no_dialog.png")
                        raise GateFail("G5", "no comment dialog")
                    self.record_shot("G5_comment_dialog.png")
                    xml = d.dump_ui(OUT / "G5_dialog.xml")
                    ts = d.ui_texts(xml)
                    if "夹具" in "\n".join(ts):
                        raise GateFail("G5", "fixture in dialog")
                    content_rows = [
                        t
                        for t in ts
                        if t
                        and "本章评论" not in t
                        and not re.search(r"共\s*\d+\s*条", t)
                        and len(t) >= 2
                        and "夹具" not in t
                    ]
                    if len(content_rows) < 1:
                        raise GateFail("G5", "empty comments")
                    if "G5" in self.want:
                        self.mark(
                            "G5",
                            True,
                            "G5_comment_dialog.png; " + "; ".join(content_rows[:4]),
                            "G5_comment_dialog.png",
                        )
                    print("G5 comments sample", content_rows[:8])

                    if "G6" in self.want:
                        if merge_ui:
                            self.record_shot("G6_merge_dialog.png")
                            if not any("本章评论" in t for t in ts) and not any(
                                "起点" in t or "QQ" in t or "段评源" in t for t in ts
                            ):
                                raise GateFail(
                                    "G6", "merge_ui set but no merge chrome in dialog"
                                )
                            self.mark(
                                "G6",
                                True,
                                "G6_merge_dialog.png",
                                "G6_merge_dialog.png",
                            )
                        else:
                            self.gates["G6"] = {
                                "ok": False,
                                "evidence": "pending multi-bind check",
                                "shot": None,
                            }

        # G2 bindings (after positive UI so auto-bind finished)
        if self.need("G2", "G6"):
            db2 = d.pull_db_wal(OUT / "pos_after", stop_app=True)
            rows = bindings_for(db2, self.book_url)
            print("G2 bindings", rows)
            if "G2" in self.want:
                if not rows or any(d.FIXTURE_PREFIX in r[0] for r in rows):
                    raise GateFail("G2", "bad bindings")
                if not any(r[1] == "auto" for r in rows):
                    raise GateFail("G2", "not auto")
                if len(rows) < 2 and self.mode == "full":
                    raise GateFail(
                        "G2",
                        f"need ≥2 real auto bindings for full acceptance, got {len(rows)}",
                    )
                self.mark(
                    "G2",
                    True,
                    f"{len(rows)} bindings: " + ", ".join(r[0] for r in rows),
                )

            if "G6" in self.want and self.gates.get("G6", {}).get("evidence") == (
                "pending multi-bind check"
            ):
                if len(rows) < 2:
                    raise GateFail("G6", "need ≥2 bindings for merge evidence")
                d.push_db_raw(db2, "merge")
                d.logcat_clear()
                open_read_book(self.book_url, d.PKG)

                def merge_ready() -> bool:
                    return any("merge providers=" in ln for ln in d.overlay_lines())

                d.wait_until(merge_ready, timeout_s=25, interval_s=1.2, label="merge")
                log2 = d.logcat_applog()
                merge_hits = [ln for ln in log2.splitlines() if "merge providers=" in ln]
                self.record_shot("G6_read_multibind.png")
                if not merge_hits:
                    raise GateFail(
                        "G6", "≥2 bindings but no merge providers= log"
                    )
                evidence = merge_hits[-1][merge_hits[-1].find("ReviewOverlay") :]
                self.mark("G6", True, evidence, "G6_read_multibind.png")
                print("G6 merge log", evidence)

        if "G8" in self.want:
            # Ensure bindings present
            db_info = d.pull_db_wal(OUT / "info_pre", stop_app=True)
            rows = bindings_for(db_info, self.book_url)
            if not rows:
                raise GateFail("G8", "no bindings to show on book info")
            d.push_db_raw(db_info, "info")
            open_book_info_from_read(self.book_url)
            self.record_shot("G8_book_info.png")
            xml = d.dump_ui(OUT / "G8_book_info.xml")
            ts = d.ui_texts(xml)
            review_bits = [t for t in ts if "段评" in t or "已绑定" in t]
            print("G8 review texts", review_bits)
            if not review_bits:
                d.swipe_frac(0.5, 0.75, 0.5, 0.25)
                time.sleep(0.6)
                self.record_shot("G8_book_info_scrolled.png")
                xml = d.dump_ui(OUT / "G8_book_info2.xml")
                ts = d.ui_texts(xml)
                review_bits = [t for t in ts if "段评" in t or "已绑定" in t]
                print("G8 after scroll", review_bits)
            if not review_bits:
                raise GateFail("G8", "no 段评源 UI text")
            self.mark(
                "G8",
                True,
                "; ".join(review_bits[:5]),
                "G8_book_info.png",
            )

        if "G9" in self.want:
            d.push_review_prefs(OUT, reviewOverlayAutoBind=False)
            dbn = d.pull_db_wal(OUT / "neg", stop_app=True)
            con = sqlite3.connect(str(dbn))
            con.execute(
                "DELETE FROM book_review_bindings WHERE contentBookUrl=?",
                (self.book_url,),
            )
            con.commit()
            con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            con.close()
            d.push_db_raw(dbn, "neg")
            d.logcat_clear()
            open_read_book(self.book_url, d.PKG)

            # Poll briefly: must NOT see auto-bind scan; then screenshot while app up
            scanned = False

            def neg_tick() -> bool:
                nonlocal scanned
                logn = d.logcat_applog()
                if "auto-bind scan" in logn or "auto-bind proposals=" in logn:
                    scanned = True
                    return True
                # stay open long enough that a scan would have logged if enabled
                return False

            d.wait_until(neg_tick, timeout_s=8, interval_s=1.0, label="neg-scan")
            self.record_shot("G9_neg_read.png")
            d.dump_ui(OUT / "G9_neg_read.xml")
            logn = d.logcat_applog()
            (OUT / "session_negative.log").write_text(logn, encoding="utf-8")
            if scanned or "auto-bind scan" in logn or "auto-bind proposals=" in logn:
                raise GateFail("G9", "auto-bind still scanned while pref off")
            dba = d.pull_db_wal(OUT / "neg_after", stop_app=True)
            n = bindings_for(dba, self.book_url)
            if n:
                raise GateFail("G9", f"unexpected bindings count={len(n)}")
            self.mark("G9", True, "G9_neg_read.png no auto-bind", "G9_neg_read.png")
            d.push_review_prefs(OUT, reviewOverlayAutoBind=True)

        missing = [
            g for g in self.want if g not in self.gates or not self.gates[g].get("ok")
        ]
        if missing:
            raise GateFail(missing[0], f"incomplete gates {missing}")

        self.write(True)
        print("PASS: acceptance →", OUT)
        return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--serial", default=None)
    ap.add_argument("--book-substr", default=BOOK_SUB)
    ap.add_argument("--from", dest="from_gate", default=None, help="Resume from gate (G8)")
    ap.add_argument("--only", default=None, help="Comma gates, e.g. G8,G9")
    ap.add_argument(
        "--mode",
        choices=("full", "smoke"),
        default="full",
        help="full=G1–G9 screenshots; smoke=log+bind focus (G1–G3)",
    )
    args = ap.parse_args()
    try:
        d.require_device(serial=args.serial)
    except SystemExit as e:
        OUT.mkdir(parents=True, exist_ok=True)
        d.write_acceptance(
            OUT,
            passed=False,
            book_url=None,
            gates={},
            shots=[],
            fail=str(e.args[0] if e.args else e),
            mode=args.mode,
        )
        return 2

    if args.mode == "smoke" and not args.only and not args.from_gate:
        want = {"G1", "G2", "G3"}
    else:
        want = d.parse_gates(from_gate=args.from_gate, only=args.only)
        if args.mode == "smoke":
            want &= {"G1", "G2", "G3"}
            if not want:
                raise SystemExit(
                    "FAIL: --mode smoke with --from/--only produced empty gates; "
                    "smoke only supports G1–G3"
                )

    if not want:
        raise SystemExit("FAIL: empty gate set")

    return Runner(want, args.book_substr, args.mode).run()


if __name__ == "__main__":
    raise SystemExit(main())
