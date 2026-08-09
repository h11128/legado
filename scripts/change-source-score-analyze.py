#!/usr/bin/env python3
"""Analyze change-source smartScore distribution from LegadoChangeSource logcat.

Usage:
  python scripts/change-source-score-analyze.py temp/legado_change_source_session_*.txt
  python scripts/change-source-score-analyze.py LOG --json temp/change_source_scores.json
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

RE_LIST = re.compile(
    r"list\+ size=\d+ visible=(?P<visible>\w+) origin=(?P<origin>\S+) "
    r"name=(?P<name>.*?) words=(?P<words>-?\d+) verdict=(?P<verdict>\S+) "
    r"score=(?P<score>-?\d+) tier=(?P<tier>\d+) respondMs=(?P<respondMs>-?\d+) "
    r"latest=(?P<latest>.*)$"
)


def parse_rows(text: str) -> list[dict]:
    """Keep the last list+ snapshot per origin (final measured state wins)."""
    by_origin: dict[str, dict] = {}
    for line in text.splitlines():
        if "list+" not in line:
            continue
        # strip logcat prefix
        idx = line.find("list+")
        payload = line[idx:]
        m = RE_LIST.match(payload)
        if not m:
            continue
        row = m.groupdict()
        row["words"] = int(row["words"])
        row["score"] = int(row["score"])
        row["tier"] = int(row["tier"])
        row["respondMs"] = int(row["respondMs"])
        row["visible"] = row["visible"] == "true"
        by_origin[row["origin"]] = row
    return list(by_origin.values())


def respond_smart_bonus(ms: int) -> int:
    if ms < 0:
        return 0
    if ms <= 200:
        return 8
    if ms <= 400:
        return 6
    if ms <= 600:
        return 4
    if ms <= 800:
        return 3
    if ms <= 1200:
        return 1
    if ms <= 2000:
        return 0
    if ms > 8000:
        return -7
    if ms > 4000:
        return -4
    return -1


def length_smart_bonus(words: int) -> int:
    if words <= 0:
        return 0
    return min(20, words // 350)


def tip_match(latest: str, local: str = "第187章 白虎不死神药跟随") -> bool | None:
    """Rough mirror of latestMatchesLocal for this sample book (num gap ≥80 ⇒ false)."""
    if not latest or not local:
        return None
    import re

    def chapter_num(t: str) -> int | None:
        m = re.search(r"第([0-9]+)[章节]", t)
        if m:
            return int(m.group(1))
        # crude chinese numerals used in sample tips — treat 六十二 etc as small ints
        cn = {
            "四十": 40,
            "四十七": 47,
            "四十八": 48,
            "五十八": 58,
            "六十二": 62,
            "六十八": 68,
            "一百八十七": 187,
        }
        m = re.search(r"第([一二三四五六七八九十百千零〇]+)[章节]", t)
        if not m:
            return None
        return cn.get(m.group(1))

    ln, cn_ = chapter_num(local), chapter_num(latest)
    if ln is not None and cn_ is not None and abs(ln - cn_) >= 80:
        return False
    if "白虎不死神药" in latest:
        return True
    if ln is not None and cn_ is None and len(latest) >= 4:
        # e.g. 乱仑系列 — no chapter num, digram fail
        return False
    if ln is not None and cn_ is not None:
        return False  # different chapter bodies in this sample
    return False


def rescore_row(row: dict, *, local_latest: str = "第187章 白虎不死神药跟随") -> int:
    """Mirror ChangeBookSourceQuality.smartScore (no refSim / userScore)."""
    base = {
        "Ok": 62,
        "Weak": 48,
        "TooShort": 28,
        "AntiTheft": 14,
        "Hijack": 10,
        "FetchError": 5,
    }.get(row["verdict"], 0)
    match = tip_match(row.get("latest") or "", local_latest)
    length = length_smart_bonus(row["words"])
    if match is False:
        length = min(length, 4)
    score = base + length + respond_smart_bonus(row["respondMs"])
    if match is True:
        score += 5
    elif match is False:
        score -= 22
    return max(0, min(100, score))


def summarize(rows: list[dict]) -> dict:
    scored = [r for r in rows if r["score"] >= 0]
    by_verdict: dict[str, list[int]] = defaultdict(list)
    for r in scored:
        by_verdict[r["verdict"]].append(r["score"])

    clusters: dict[str, dict] = {}
    for verdict, scores in sorted(by_verdict.items()):
        scores = sorted(scores)
        clusters[verdict] = {
            "n": len(scores),
            "min": scores[0],
            "max": scores[-1],
            "span": scores[-1] - scores[0],
            "unique": len(set(scores)),
            "histogram": dict(Counter(scores)),
            "median": scores[len(scores) // 2],
        }

    ok_rows = [r for r in scored if r["verdict"] == "Ok"]
    ok_same_score = Counter(r["score"] for r in ok_rows)
    crowded = {str(k): v for k, v in ok_same_score.items() if v >= 3}

    rescored = []
    for r in scored:
        nr = dict(r)
        nr["score_new"] = rescore_row(r)
        rescored.append(nr)
    ok_new = [r["score_new"] for r in rescored if r["verdict"] == "Ok"]
    ok_new_hist = dict(Counter(ok_new)) if ok_new else {}

    return {
        "origins": len(rows),
        "scored": len(scored),
        "visible_scored": sum(1 for r in scored if r["visible"]),
        "verdict_counts": dict(Counter(r["verdict"] for r in scored)),
        "clusters": clusters,
        "ok_crowded_scores": crowded,
        "ok_rescore_unique": len(set(ok_new)),
        "ok_rescore_span": (max(ok_new) - min(ok_new)) if ok_new else 0,
        "ok_rescore_histogram": {str(k): v for k, v in sorted(ok_new_hist.items())},
        "ok_top": sorted(
            (
                {
                    "score": r["score"],
                    "score_new": rescore_row(r),
                    "words": r["words"],
                    "respondMs": r["respondMs"],
                    "name": r["name"][:40],
                    "origin": r["origin"][:60],
                    "latest": (r["latest"] or "")[:40],
                }
                for r in ok_rows
            ),
            key=lambda x: (-x["score_new"], -x["words"], x["respondMs"]),
        )[:25],
        "too_short_sample": sorted(
            (
                {
                    "score": r["score"],
                    "score_new": rescore_row(r),
                    "words": r["words"],
                    "respondMs": r["respondMs"],
                    "name": r["name"][:40],
                }
                for r in scored
                if r["verdict"] == "TooShort"
            ),
            key=lambda x: -x["score_new"],
        )[:15],
    }


def report_md(data: dict, log: Path) -> str:
    lines = [
        f"# Change-source smartScore analyze",
        "",
        f"- Log: `{log.as_posix()}`",
        f"- Origins (last list+): **{data['origins']}** · scored **{data['scored']}** · visible **{data['visible_scored']}**",
        f"- Verdicts: `{json.dumps(data['verdict_counts'], ensure_ascii=False)}`",
        "",
        "## Score clusters by verdict",
        "",
        "| Verdict | n | min | median | max | span | unique |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for verdict, c in data["clusters"].items():
        lines.append(
            f"| {verdict} | {c['n']} | {c['min']} | {c['median']} | {c['max']} | "
            f"{c['span']} | {c['unique']} |"
        )
    lines += [
        "",
        f"## Ok crowded scores (≥3 origins)",
        "",
        f"`{json.dumps(data['ok_crowded_scores'], ensure_ascii=False)}`",
        "",
        f"## Rescore preview (length-continuous formula)",
        "",
        f"- Ok unique: **{data.get('ok_rescore_unique')}** · span **{data.get('ok_rescore_span')}**",
        f"- Ok hist: `{json.dumps(data.get('ok_rescore_histogram'), ensure_ascii=False)}`",
        "",
        "## Ok top (by score_new)",
        "",
    ]
    for r in data["ok_top"]:
        lines.append(
            f"- **{r.get('score_new', r['score'])}** (was {r['score']}) · words={r['words']} · "
            f"{r['respondMs']}ms · {r['name']} · latest={r['latest']}"
        )
    lines += ["", "## TooShort sample", ""]
    for r in data["too_short_sample"]:
        lines.append(
            f"- **{r.get('score_new', r['score'])}** (was {r['score']}) · words={r['words']} · "
            f"{r['respondMs']}ms · {r['name']}"
        )
    return "\n".join(lines) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("log", type=Path)
    ap.add_argument("--json", type=Path, default=None)
    ap.add_argument("--md", type=Path, default=None)
    args = ap.parse_args()
    text = args.log.read_text(encoding="utf-8", errors="replace")
    rows = parse_rows(text)
    data = summarize(rows)
    data["rows"] = rows
    md = report_md(data, args.log)
    print(md)
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        # rows included for offline re-score experiments
        args.json.write_text(
            json.dumps(data, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        print(f"Wrote {args.json}", file=sys.stderr)
    if args.md:
        args.md.parent.mkdir(parents=True, exist_ok=True)
        args.md.write_text(md, encoding="utf-8")
        print(f"Wrote {args.md}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
