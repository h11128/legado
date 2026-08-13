# RFC-005 device screenshots

Captured 2026-08-13 on SM-A366U1.

PR1 needs **both** the merge-success pair and the do-not-guess pair.

| File | What |
|---|---|
| `pr1-happy-before.png` | Upstream APK: search 科技帝国从穿越三体开始, **佚名** is its own card under 西门要吹雪 |
| `pr1-happy-after.png` | Fork debug: 佚名 merged into 西门要吹雪 (origins 4 → 13) |
| `pr1-before.png` | Upstream APK: search 天才之上, **佚名** is its own card |
| `pr1-after.png` | Fork debug: 一桶布丁 / 来一包坚果吧 still split; empty author stays its own card (`|S|≥2` do not guess) |
| `pr2-menu.png` | Change-source menu: new toggles vs upstream items |
| `pr2-after.png` | Live 换源: two-line progress + mismatch badge |

PR1 before is `com.legado.app.release` (GitHub universal APK), after is `com.legado.app.debug`.

```bash
python scripts/rfc005-capture-pr1-before.py --query 科技帝国从穿越三体开始 --skip-install --out-dir docs/design/rfc-005-assets --stem pr1-happy
python scripts/rfc005-capture-pr1-before.py --query 天才之上 --skip-install --out-dir docs/design/rfc-005-assets --stem pr1
python scripts/rfc005-pr-screenshot-session.py --no-prefs --only cs
```
