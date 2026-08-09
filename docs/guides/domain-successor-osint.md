# Domain successor OSINT (hunt companion)

When `source-cli hunt --probe` is empty **or** the site looks parked/广告壳 but the
brand may have moved, agents must run this pass before claiming「无后继 / 修不了」。

## Command (legado repo)

```bash
python scripts/domain-successor-hunt.py --url 'https://www.example.cc/' \
  --title '书架书名1' --title '书架书名2' \
  --out temp/full_fix/cache/<host>/successor_hunt.json
```

Then open **Google** (browser) and run every query printed under `google_queries`.

Optional paid DNS history:

```bash
export SECURITYTRAILS_API_KEY=…
python scripts/domain-successor-hunt.py --url '…'
```

## Wayback rate limit (MUST)

- **Never** parallel `curl` to `web.archive.org` / `archive.org`.
- Only use `scripts/lib/wayback_cdx.py` (`cdx_search` / `available` / `wayback_get`).
- Shared lock: `temp/full_fix/cache/wayback_rate_lock.json`
- Default min interval: **12s** (`WAYBACK_MIN_INTERVAL_S`)
- On HTTP **429**: exponential backoff (30s→… capped 300s), then retry
  (`WAYBACK_MAX_RETRIES`, default 6)

## Policy links

- Skill trap: `hunt_osint_skipped` — `legadoSkill/skills/legado-book-source-repair/SKILL.md`
- Discipline §5c / §12b — `.cursor/rules/book-source-repair-discipline.mdc`
- Seed hunt policy — `legadoSkill/docs/domain-hunt-trial-2026-07-26.md`
