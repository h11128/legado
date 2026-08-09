/**
 * RFC-004 real review provider — 微信读书（热门划线）
 *
 * No-login path uses weread.qq.com web APIs:
 *   - search: /web/search/global
 *   - reviews: /web/book/bestbookmarks (popular highlights)
 *
 * Full personal notes / chapterInfos need WebView login (loginUrl).
 * Chapter TOC without login is limited to chapters that appear in bestbookmarks.
 */
var config = {
    bookSourceUrl: "https://weread.qq.com#rfc004-review",
    bookSourceName: "微信读书划线(段评源)",
    bookSourceType: 0,
    bookSourceGroup: "段评源",
    bookSourceComment: "RFC-004 real provider. Uses popular highlights as chapter/paragraph comments. Login unlocks more APIs.",
    enabledCookieJar: true,
    loginUrl: "https://weread.qq.com/",
    header: JSON.stringify({
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer": "https://weread.qq.com/"
    }),
    loginUi: [],
    exploreUrl: [],
    lastUpdateTime: 1786242000000
};

function parseJson(raw) {
    return JSON.parse(String(raw || "{}"));
}

function bookIdFromUrl(url) {
    var s = String(url || "");
    var m = s.match(/[?&]bookId=([^&]+)/);
    if (m) return decodeURIComponent(m[1]);
    m = s.match(/\/book\/([^/?#]+)/);
    return m ? decodeURIComponent(m[1]) : "";
}

function chapterUidFromUrl(url) {
    var s = String(url || "");
    var m = s.match(/[?&]chapterUid=([^&]+)/);
    if (m) return decodeURIComponent(m[1]);
    m = s.match(/\/chapter\/([^/?#]+)/);
    return m ? decodeURIComponent(m[1]) : "";
}

function bestBookmarks(bookId, chapterUid) {
    var url = "https://weread.qq.com/web/book/bestbookmarks?bookId=" + encodeURIComponent(bookId);
    if (chapterUid) url += "&chapterUid=" + encodeURIComponent(chapterUid);
    var json = parseJson(java.ajax(url));
    if (json.errCode) throw "微信读书 bestbookmarks 失败: " + (json.errMsg || json.errCode);
    var bb = json.bestBookMarks || json.bestBookmarks;
    if (!bb || typeof bb !== "object") {
        throw "微信读书 bestbookmarks 缺少 bestBookMarks 字段";
    }
    return bb;
}

function search(key, page) {
    if (page > 1) return [];
    var url = "https://weread.qq.com/web/search/global?keyword=" + encodeURIComponent(String(key || "")) +
        "&maxIdx=0&count=10&fragmentSize=120";
    var json = parseJson(java.ajax(url));
    var books = json.books || [];
    return books.map(function (row) {
        var b = row.bookInfo || row;
        var bookId = String(b.bookId || "");
        return {
            name: String(b.title || ""),
            author: String(b.author || ""),
            coverUrl: String(b.cover || ""),
            intro: String(b.intro || ""),
            bookUrl: "https://weread.qq.com/web/book?bookId=" + encodeURIComponent(bookId),
            latestChapterTitle: ""
        };
    }).filter(function (b) { return b.name && bookIdFromUrl(b.bookUrl); });
}

function getBookInfo(book) {
    var bookId = bookIdFromUrl(book.bookUrl);
    var bb = bestBookmarks(bookId);
    var chapters = bb.chapters || [];
    return {
        name: String(book.name || ""),
        author: String(book.author || ""),
        intro: String(book.intro || "微信读书热门划线段评源"),
        tocUrl: book.bookUrl,
        latestChapterTitle: chapters.length ? String(chapters[chapters.length - 1].title || "") : ""
    };
}

function getChapters(book) {
    var bookId = bookIdFromUrl(book.bookUrl || book.tocUrl);
    var bb = bestBookmarks(bookId);
    var chapters = bb.chapters || [];
    // Deduplicate by chapterUid; bestbookmarks only returns chapters with popular highlights.
    var seen = {};
    var out = [];
    for (var i = 0; i < chapters.length; i++) {
        var c = chapters[i];
        var uid = String(c.chapterUid || "");
        if (!uid || seen[uid]) continue;
        seen[uid] = true;
        out.push({
            title: String(c.title || ("章节" + uid)),
            url: "https://weread.qq.com/web/book?bookId=" + encodeURIComponent(bookId) +
                "&chapterUid=" + encodeURIComponent(uid)
        });
    }
    if (!out.length) {
        out.push({
            title: "热门划线合集",
            url: "https://weread.qq.com/web/book?bookId=" + encodeURIComponent(bookId) + "&chapterUid=0"
        });
    }
    return out;
}

function getContent(chapter, book, nextChapterUrl) {
    var bookId = bookIdFromUrl(chapter.url) || bookIdFromUrl(book.bookUrl);
    var chapterUid = chapterUidFromUrl(chapter.url);
    var bb = bestBookmarks(bookId, chapterUid && chapterUid !== "0" ? chapterUid : null);
    var items = (bb.items || []).filter(function (it) {
        if (!chapterUid || chapterUid === "0") return true;
        return String(it.chapterUid) === String(chapterUid);
    });
    if (!items.length) {
        return "（本章暂无热门划线正文预览；段评仍可从划线接口加载）";
    }
    return items.map(function (it) {
        return String(it.markText || "").trim();
    }).filter(Boolean).join("\n\n");
}

function getReviewSummary(chapter, book) {
    var bookId = bookIdFromUrl(chapter.url) || bookIdFromUrl(book.bookUrl);
    var chapterUid = chapterUidFromUrl(chapter.url);
    var bb = bestBookmarks(bookId, chapterUid && chapterUid !== "0" ? chapterUid : null);
    var items = (bb.items || []).filter(function (it) {
        if (!chapterUid || chapterUid === "0") return true;
        return String(it.chapterUid) === String(chapterUid);
    });
    var total = 0;
    var summary = [];
    for (var i = 0; i < items.length; i++) {
        var it = items[i];
        var count = Number(it.totalCount || 1);
        total += count;
        summary.push({
            paraIndex: i + 1,
            count: count,
            paraData: String(it.range || it.bookmarkId || (i + 1))
        });
    }
    if (total > 0) {
        summary.unshift({
            paraIndex: -1,
            count: total,
            paraData: "chapter:" + String(chapterUid || "")
        });
    }
    return summary;
}

function getReviewDetail(chapter, book, paraIndex, paraData, page) {
    var bookId = bookIdFromUrl(chapter.url) || bookIdFromUrl(book.bookUrl);
    var chapterUid = chapterUidFromUrl(chapter.url);
    var bb = bestBookmarks(bookId, chapterUid && chapterUid !== "0" ? chapterUid : null);
    var items = (bb.items || []).filter(function (it) {
        if (!chapterUid || chapterUid === "0") return true;
        return String(it.chapterUid) === String(chapterUid);
    });
    var pageNum = Number(page || 1);
    if (pageNum < 1) pageNum = 1;
    var pageSize = 20;

    function itemToReviews(it) {
        var users = it.users || [];
        var mark = String(it.markText || "");
        var count = Number(it.totalCount || users.length || 1);
        if (!users.length) {
            return [{
                id: String(it.bookmarkId || paraData || ""),
                name: "热门划线",
                content: mark + "（" + count + " 人划线）",
                replies: []
            }];
        }
        return users.map(function (u, idx) {
            return {
                id: String(it.bookmarkId || "") + ":" + idx,
                name: String(u.name || "读者"),
                avatar: String(u.avatar || ""),
                badge: idx === 0 ? (count + "人划线") : "",
                content: mark,
                replies: []
            };
        });
    }

    if (Number(paraIndex) === -1) {
        var all = [];
        for (var i = 0; i < items.length; i++) {
            all = all.concat(itemToReviews(items[i]));
        }
        var start = (pageNum - 1) * pageSize;
        var slice = all.slice(start, start + pageSize);
        return {
            items: slice,
            nextPageUrl: start + slice.length < all.length ? "more" : null
        };
    }

    var idx = Number(paraIndex) - 1;
    var hit = null;
    if (paraData) {
        for (var j = 0; j < items.length; j++) {
            if (String(items[j].range) === String(paraData) || String(items[j].bookmarkId) === String(paraData)) {
                hit = items[j];
                break;
            }
        }
    }
    if (!hit && idx >= 0 && idx < items.length) hit = items[idx];
    if (!hit) return { items: [], nextPageUrl: null };
    var detail = itemToReviews(hit);
    var dStart = (pageNum - 1) * pageSize;
    var dSlice = detail.slice(dStart, dStart + pageSize);
    return {
        items: dSlice,
        nextPageUrl: dStart + dSlice.length < detail.length ? "more" : null
    };
}
