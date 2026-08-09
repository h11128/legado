#!/usr/bin/env python3
"""Probe 起点本章说: paragraphId namespace vs getContent <p>/blank-line split.

Loads docs/design/sources/qidian-review-provider.js into MCP eval_js and compares
summary paraIndex set to 1..N from content split.

Usage:
  python scripts/rfc004-probe-qidian-para-align.py
  python scripts/rfc004-probe-qidian-para-align.py --bid 1010868264 --cid 402733549
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from scripts.lib.legado_mcp import LegadoMcp, tool_is_error, tool_text  # noqa: E402

SRC = ROOT / "docs" / "design" / "sources" / "qidian-review-provider.js"
OUT = ROOT / "temp" / "rfc004_qidian_para_probe_out.txt"
PROVIDER = "https://m.qidian.com#rfc004-review"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bid", default="1010868264")
    ap.add_argument("--cid", default="402733549")
    ap.add_argument("--title", default="第一章 绯红")
    ap.add_argument("--name", default="诡秘之主")
    ap.add_argument("--author", default="爱潜水的乌贼")
    args = ap.parse_args()

    book_url = f"https://m.qidian.com/book/{args.bid}/"
    chapter_url = f"https://m.qidian.com/book/{args.bid}/{args.cid}"
    probe = f"""
var bookUrl={json.dumps(book_url)};
var chapter={{title:{json.dumps(args.title)}, url:{json.dumps(chapter_url)}}};
var book={{bookUrl:bookUrl, name:{json.dumps(args.name)}, author:{json.dumps(args.author)}}};
var content=getContent(chapter, book, null);
var paras=String(content||'').split(/\\n\\n+/).filter(function(s){{return String(s).trim();}});
var summary=getReviewSummary(chapter, book);
var body=summary.filter(function(x){{return x.paraIndex>0;}});
var ids=body.map(function(x){{return x.paraIndex;}});
var sorted=ids.slice().sort(function(a,b){{return a-b;}});
var idEqualsPosition = ids.length>0 && ids.every(function(id,i){{return id===i+1;}});
var overlapPos = 0;
for (var i=1;i<=paras.length;i++){{ if (ids.indexOf(i)>=0) overlapPos++; }}
JSON.stringify({{
  pSplit: paras.length,
  summaryBody: body.length,
  chapterBucket: (summary.filter(function(x){{return x.paraIndex===-1;}})[0]||{{}}).count,
  firstIds: sorted.slice(0,12),
  maxId: sorted.length?sorted[sorted.length-1]:null,
  idEqualsPosition: idEqualsPosition,
  overlapWith1toN: overlapPos,
  sample: body.slice(0,5)
}});
"""
    js = SRC.read_text(encoding="utf-8") + "\n" + probe
    mcp = LegadoMcp(timeout=120)
    raw = mcp.call(
        "eval_js",
        {"js": js, "url": PROVIDER, "timeoutSec": 90},
    )
    text = tool_text(raw)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    if tool_is_error(raw):
        print(text, file=sys.stderr)
        return 1
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        print("FAIL: no JSON in eval result", file=sys.stderr)
        print(text, file=sys.stderr)
        return 1
    data = json.loads(m.group(0))
    print(json.dumps(data, ensure_ascii=False, indent=2))
    print(f"wrote {OUT}")
    # Informative exit: id namespace match is required for naive hardMap keys.
    if not data.get("idEqualsPosition"):
        print(
            "NOTE: paragraphId is NOT 1..N — hardMap position keys will miss summary",
            file=sys.stderr,
        )
        return 2
    print("OK: paragraphId == 1..N for this chapter (count≠id; see sample[].count)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
