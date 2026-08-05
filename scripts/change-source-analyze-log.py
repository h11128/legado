#!/usr/bin/env python3
"""Analyze a LegadoChangeSource logcat dump and print / write a session report.

Usage:
  python scripts/change-source-analyze-log.py temp/legado_change_source_selftest_2026-08-05.txt
  python scripts/change-source-analyze-log.py LOG --out docs/reference/change-source-selftest-DATE.md
  python scripts/change-source-analyze-log.py LOG --json temp/change_source_analyze.json
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path


RE_START = re.compile(
    r"start book=(?P<book>.*?) author=(?P<author>.*?) sources=(?P<sources>\d+) "
    r"threads=(?P<threads>\d+) deepParallel=(?P<deepParallel>\d+).*?"
    r"loadWordCount=(?P<loadWordCount>\w+) earlyStop=(?P<earlyStop>\w+) "
    r"earlyStopTarget=(?P<earlyStopTarget>\d+)"
)
RE_PROGRESS = re.compile(
    r"progress done=(?P<done>\d+)/(?P<total>\d+) inFlight=(?P<inFlight>\d+)/(?P<askCap>\d+) "
    r"deep=(?P<deep>\d+)/(?P<deepCap>\d+) list=(?P<list>\d+) qualityOk=(?P<qualityOk>\d+) "
    r"hits=(?P<hits>\d+) published=(?P<published>\d+) early=(?P<early>\w+) finished=(?P<finished>\w+)"
)
RE_FINISH = re.compile(
    r"finish cause=(?P<cause>\S+) early=(?P<early>\w+) completed=(?P<completed>\d+)/(?P<total>\d+) "
    r"list=(?P<list>\d+) qualityOk=(?P<qualityOk>\d+) hits=(?P<hits>\d+) published=(?P<published>\d+) "
    r"missEmpty=(?P<missEmpty>\d+) missTimeout=(?P<missTimeout>\d+) missError=(?P<missError>\d+) "
    r"missContentBad=(?P<missContentBad>\d+)"
)
RE_EARLY = re.compile(r"early-stop qualityOk=(?P<qualityOk>\d+) target=(?P<target>\d+)")
RE_WORD = re.compile(r"phase word-eval origin=\S+ reason=(?P<reason>\S+)")
RE_DROP = re.compile(r"list- drop origin=\S+ reason=(?P<reason>\S+)")
RE_LIST_PLUS = re.compile(r"list\+")
RE_HIT = re.compile(r"\bhit origin=")


def parse(text: str) -> dict:
    start = RE_START.search(text)
    finish = None
    for m in RE_FINISH.finditer(text):
        finish = m
    early = RE_EARLY.search(text)
    progresses = list(RE_PROGRESS.finditer(text))
    word_reasons = Counter(m.group("reason").split("(")[0] for m in RE_WORD.finditer(text))
    drop_reasons = Counter(m.group("reason") for m in RE_DROP.finditer(text))

    max_in_flight = max((int(m.group("inFlight")) for m in progresses), default=0)
    max_deep = max((int(m.group("deep")) for m in progresses), default=0)
    deep_cap = int(progresses[0].group("deepCap")) if progresses else 0
    ask_cap = int(progresses[0].group("askCap")) if progresses else 0

    gates = {
        "has_start": start is not None,
        "has_finish": finish is not None,
        "ask_parallel_ok": max_in_flight >= max(1, ask_cap // 2) if ask_cap else False,
        "deep_within_cap": max_deep <= deep_cap if deep_cap else True,
        "list_drop_on_bad": drop_reasons.get("content-bad", 0) > 0
        or word_reasons.get("ok", 0) + word_reasons.get("ok_stitch_override", 0) > 0,
        "quality_ok_useful": (int(finish.group("qualityOk")) if finish else 0) >= 5
        or (early is not None),
        "early_stop_honored": early is not None
        or (finish is not None and finish.group("early") == "false"),
    }
    # If earlyStop pref was on and target reached, expect early=true
    if start and start.group("earlyStop") == "true" and early:
        gates["early_stop_honored"] = True
    elif start and start.group("earlyStop") == "true" and finish and finish.group("early") != "true":
        # finished full pool without early — only fail if qualityOk never hit target
        target = int(start.group("earlyStopTarget"))
        q = int(finish.group("qualityOk")) if finish else 0
        gates["early_stop_honored"] = q < target

    failed = [k for k, v in gates.items() if not v]
    verdict = "PASS" if not failed else ("FAIL" if any(
        k in failed for k in ("has_start", "has_finish", "ask_parallel_ok")
    ) else "PASS (partial)")

    return {
        "verdict": verdict,
        "failed_gates": failed,
        "gates": gates,
        "start": {k: start.group(k) for k in start.groupdict()} if start else None,
        "finish": {k: finish.group(k) for k in finish.groupdict()} if finish else None,
        "early_stop": {k: early.group(k) for k in early.groupdict()} if early else None,
        "max_in_flight": max_in_flight,
        "max_deep": max_deep,
        "ask_cap": ask_cap,
        "deep_cap": deep_cap,
        "list_plus": len(RE_LIST_PLUS.findall(text)),
        "hits": len(RE_HIT.findall(text)),
        "word_eval_reasons": dict(word_reasons),
        "drop_reasons": dict(drop_reasons),
        "progress_samples": [
            {k: m.group(k) for k in m.groupdict()}
            for m in progresses
            if int(m.group("qualityOk")) in (0, 1, 5, 10, 15, 20)
            or m.group("finished") == "true"
        ][:12],
    }


def to_markdown(data: dict, log_path: str) -> str:
    s = data.get("start") or {}
    f = data.get("finish") or {}
    e = data.get("early_stop")
    lines = [
        f"# Change-source log analyze",
        "",
        f"- Log: `{log_path}`",
        f"- Verdict: **{data['verdict']}**",
        f"- Failed gates: `{', '.join(data['failed_gates']) or '(none)'}`",
        "",
        "## Session",
        "",
        f"| Field | Value |",
        f"|---|---|",
        f"| book | {s.get('book', '?')} / {s.get('author', '?')} |",
        f"| sources / threads / deepParallel | {s.get('sources')} / {s.get('threads')} / {s.get('deepParallel')} |",
        f"| prefs | loadWordCount={s.get('loadWordCount')} earlyStop={s.get('earlyStop')} target={s.get('earlyStopTarget')} |",
        f"| max inFlight / askCap | {data['max_in_flight']} / {data['ask_cap']} |",
        f"| max deep / deepCap | {data['max_deep']} / {data['deep_cap']} |",
        f"| hits / list+ | {data['hits']} / {data['list_plus']} |",
        f"| early-stop | {e or '(none)'} |",
        f"| finish | {f or '(none)'} |",
        "",
        "## Word-eval reasons",
        "",
        "```json",
        json.dumps(data["word_eval_reasons"], ensure_ascii=False, indent=2),
        "```",
        "",
        "## List-drop reasons",
        "",
        "```json",
        json.dumps(data["drop_reasons"], ensure_ascii=False, indent=2),
        "```",
        "",
        "## Gates",
        "",
    ]
    for k, v in data["gates"].items():
        lines.append(f"- `{'PASS' if v else 'FAIL'}` {k}")
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("log", type=Path)
    ap.add_argument("--out", type=Path, help="Write markdown report")
    ap.add_argument("--json", dest="json_out", type=Path, help="Write JSON summary")
    ap.add_argument("--expect-deep-cap", action="store_true",
                    help="Fail if max deep > deepCap (strict concurrency gate)")
    args = ap.parse_args()
    text = args.log.read_text(encoding="utf-8", errors="replace")
    data = parse(text)
    if args.expect_deep_cap and not data["gates"]["deep_within_cap"]:
        data["verdict"] = "FAIL"
        if "deep_within_cap" not in data["failed_gates"]:
            data["failed_gates"].append("deep_within_cap")

    print(to_markdown(data, str(args.log).replace("\\", "/")))
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(to_markdown(data, str(args.log).replace("\\", "/")), encoding="utf-8")
        print(f"Wrote {args.out}", file=sys.stderr)
    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"Wrote {args.json_out}", file=sys.stderr)

    if data["verdict"].startswith("FAIL"):
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
