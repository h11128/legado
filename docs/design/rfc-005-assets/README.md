# RFC-005 device screenshots

Captured 2026-08-13 on SM-A366U1.

**Ship only 3 pairs** (not before/after for every PR):

| File | What |
|---|---|
| `pr1-before.png` | Upstream APK `3.26081201`: search 天才之上, **佚名** is its own card |
| `pr1-after.png` | Fork debug: 佚名 gone, 一桶布丁 origin badge 11→20 |
| `pr2-menu.png` | Change-source menu: new toggles vs upstream items |
| `pr2-after.png` | Live 换源: two-line progress + mismatch badge |

PR1 before is `com.legado.app.release` (GitHub universal APK), after is `com.legado.app.debug`.

```bash
python scripts/rfc005-capture-pr1-before.py --query 天才之上 --skip-install
python scripts/rfc005-pr-screenshot-session.py --no-prefs --only cs
```
