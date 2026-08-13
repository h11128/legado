# RFC-005 device screenshots

Captured 2026-08-13 on SM-A366U1 (`com.legado.app.debug`).

Regenerate:

```bash
python scripts/rfc005-pr-screenshot-session.py
python scripts/rfc005-pr-screenshot-session.py --no-prefs --only pr1,pr7,pr6
```

| File | PR | What it shows |
|---|---|---|
| `pr1-after.png` | 1 | Search results for a shelf title (merge-on; no merge-off build for before) |
| `pr2-menu.png` | 2–3 | Overflow menu: new toggles checked vs upstream items |
| `pr2-after.png` | 2 | Live 换源 list with filters on |
| `pr3-still.png` | 3 | Same live session (screenrecord encoder failed on this device) |
| `pr4-after.png` | 4 | Two-line progress: 结果/命中/已问/问中 |
| `pr5-after.png` | 5 | Badge「最新章节疑似不一致」 |
| `pr6-after.png` | 6 | Auto-change strip「自动换源·已问 n/30」 |
| `pr7-settings.png` | 7 | Read settings: 跨源段评 / 自动发现 / 图标 / 多源合集 |
