# Source repair retrospective


## 2026-08-06 肉文小说 author
- URL: https://www.xn--7dv141d.com/
- Bug: search `class.author@text` concat sibling 阅读量; detail missing author
- Fix: search `class.author.0@text##作者：`; bookInfo author from booktag a.0
- Verify: debug author=新乙; check 校验成功 (~3.8s)
- 晴天: one aggregator `v1.gyks.cf` with source=全部 → multi SearchBook same originName

## 2026-08-06 海词 dict.cn
- Fake name from URL decode fixed: bookList empty on 词条未找到; name from h1/.word
- Verify: novel key → 0 books; hello check 校验成功
- App: isAcceptableChangeSourceHit (usable latest, author when local set, non-book intro)
