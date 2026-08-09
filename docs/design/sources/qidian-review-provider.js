/**
 * RFC-004 real review provider — 起点中文网（本章说）
 *
 * Uses m.qidian.com majax APIs (PC read.qidian.com is WAF-blocked).
 * CookieJar keeps `_csrfToken`. Optional WebView login for VIP chapters.
 *
 * Capability: getReviewSummary + getReviewDetail (chapter-bucket + paragraph segments).
 * Paragraph icons (P2) still need ContentSplitVerified authority in app — chapter-bucket works now.
 */
var config = {
    bookSourceUrl: "https://m.qidian.com#rfc004-review",
    bookSourceName: "起点本章说(段评源)",
    bookSourceType: 0,
    bookSourceGroup: "段评源",
    bookSourceComment: "RFC-004 real provider. Mobile majax chapterReview. Prefer bind as 段评源; content can be any origin.",
    enabledCookieJar: true,
    loginUrl: "https://m.qidian.com/",
    header: JSON.stringify({
        "User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
        "Referer": "https://m.qidian.com/",
        "X-Requested-With": "XMLHttpRequest"
    }),
    loginUi: [],
    exploreUrl: [],
    lastUpdateTime: 1786242000000
};

function ensureCsrf() {
    var all = String(cookie.getCookie("https://m.qidian.com") || "");
    var m = all.match(/(?:^|;\s*)_csrfToken=([^;]+)/);
    if (m) return m[1];
    java.ajax("https://m.qidian.com/");
    all = String(cookie.getCookie("https://m.qidian.com") || "");
    m = all.match(/(?:^|;\s*)_csrfToken=([^;]+)/);
    if (!m) throw "无法获取起点 _csrfToken，请打开书源登录页刷新 Cookie";
    return m[1];
}

function majax(pathAndQuery) {
    var csrf = ensureCsrf();
    var join = pathAndQuery.indexOf("?") >= 0 ? "&" : "?";
    var url = "https://m.qidian.com/majax/" + pathAndQuery + join + "_csrfToken=" + encodeURIComponent(csrf);
    var raw = String(java.ajax(url) || "");
    var json;
    try {
        json = JSON.parse(raw);
    } catch (e) {
        throw "起点非 JSON 响应: " + raw.slice(0, 120);
    }
    if (json.code !== 0) {
        throw "起点接口失败 code=" + json.code + " msg=" + (json.msg || "");
    }
    return json.data || {};
}

function bookIdFromUrl(url) {
    var s = String(url || "");
    var m = s.match(/\/book\/(\d+)/);
    return m ? m[1] : "";
}

function chapterIdFromUrl(url) {
    var s = String(url || "");
    var m = s.match(/\/book\/\d+\/(\d+)/);
    if (m) return m[1];
    m = s.match(/[?&]chapterId=(\d+)/);
    return m ? m[1] : "";
}

function search(key, page) {
    if (page > 1) return [];
    var data = majax("search/list?kw=" + encodeURIComponent(String(key || "")) + "&pageNum=1&pageSize=10");
    var records = (((data.bookInfo || {}).records) || []);
    return records.map(function (b) {
        var bid = String(b.bid || b.bookId || "");
        return {
            name: String(b.bName || b.bookName || ""),
            author: String(b.bAuth || b.authorName || ""),
            coverUrl: String(b.bCover || b.cover || ""),
            intro: String(b.desc || ""),
            bookUrl: "https://m.qidian.com/book/" + bid + "/",
            latestChapterTitle: String(b.lChapterName || b.lastChapterName || "")
        };
    }).filter(function (b) { return b.name && b.bookUrl; });
}

function getBookInfo(book) {
    var bid = bookIdFromUrl(book.bookUrl);
    var data = majax("book/category?bookId=" + encodeURIComponent(bid));
    var lastTitle = "";
    var vs = data.vs || [];
    if (vs.length) {
        var lastVol = vs[vs.length - 1];
        var cs = lastVol.cs || [];
        if (cs.length) lastTitle = String(cs[cs.length - 1].cN || "");
    }
    return {
        name: String(data.bookName || book.name || ""),
        author: String(data.authorName || book.author || ""),
        intro: String(data.desc || book.intro || ""),
        tocUrl: "https://m.qidian.com/book/" + bid + "/",
        latestChapterTitle: lastTitle || String(book.latestChapterTitle || "")
    };
}

function getChapters(book) {
    var bid = bookIdFromUrl(book.bookUrl || book.tocUrl);
    var data = majax("book/category?bookId=" + encodeURIComponent(bid));
    var chapters = [];
    var vs = data.vs || [];
    for (var i = 0; i < vs.length; i++) {
        var cs = vs[i].cs || [];
        for (var j = 0; j < cs.length; j++) {
            var c = cs[j];
            chapters.push({
                title: String(c.cN || ""),
                url: "https://m.qidian.com/book/" + bid + "/" + c.id
            });
        }
    }
    return chapters;
}

function getContent(chapter, book, nextChapterUrl) {
    var bid = bookIdFromUrl(chapter.url) || bookIdFromUrl(book.bookUrl);
    var cid = chapterIdFromUrl(chapter.url);
    var data = majax("chapter/getChapterInfo?bookId=" + encodeURIComponent(bid) + "&chapterId=" + encodeURIComponent(cid));
    var html = String(((data.chapterInfo || {}).content) || "");
    if (!html) return "（本章正文不可用，仍可尝试加载本章说）";
    // Convert <p>… segments to blank-line paragraphs matching 本章说 segmentId order.
    var parts = html.split(/<p[^>]*>/i).map(function (s) {
        return String(s || "").replace(/<[^>]+>/g, "").replace(/\u3000/g, " ").trim();
    }).filter(function (s) { return !!s; });
    return parts.join("\n\n");
}

function getReviewSummary(chapter, book) {
    var bid = bookIdFromUrl(chapter.url) || bookIdFromUrl(book.bookUrl);
    var cid = chapterIdFromUrl(chapter.url);
    var data = majax("chapterReview/reviewSummary?bookId=" + encodeURIComponent(bid) + "&chapterId=" + encodeURIComponent(cid));
    var list = data.list || [];
    return list.map(function (item) {
        var paraIndex = Number(item.paragraphId);
        // Device raw (2026-08-09): textCount is the review count (para -1 → 11174 matches reviewList.total).
        var count = Number(item.textCount || item.reviewNum || item.count || 0);
        return {
            paraIndex: paraIndex,
            count: count,
            paraData: String(paraIndex)
        };
    }).filter(function (item) {
        return (item.paraIndex === -1 || item.paraIndex > 0) && item.count > 0;
    });
}

function getReviewDetail(chapter, book, paraIndex, paraData, page) {
    var bid = bookIdFromUrl(chapter.url) || bookIdFromUrl(book.bookUrl);
    var cid = chapterIdFromUrl(chapter.url);
    var seg = (paraData != null && String(paraData) !== "") ? String(paraData) : String(paraIndex);
    var data = majax(
        "chapterReview/reviewList?bookId=" + encodeURIComponent(bid) +
        "&chapterId=" + encodeURIComponent(cid) +
        "&segmentId=" + encodeURIComponent(seg) +
        "&type=2&page=" + encodeURIComponent(String(page || 1)) +
        "&pageSize=20"
    );
    var items = (data.list || []).map(function (r) {
        var replies = (r.replyList || []).map(function (rep) {
            return {
                id: String(rep.reviewId || ""),
                name: String(rep.nickName || ""),
                avatar: String(rep.avatar || ""),
                content: String(rep.content || "")
            };
        });
        return {
            id: String(r.reviewId || ""),
            name: String(r.nickName || ""),
            avatar: String(r.avatar || ""),
            badge: r.essenceStatus ? "精华" : (r.authorReview ? "作者" : ""),
            content: String(r.content || ""),
            replies: replies
        };
    });
    var total = Number(data.total || 0);
    var loaded = (Number(page || 1) - 1) * 20 + items.length;
    var hasMore = total > 0 ? loaded < total : items.length >= 20;
    return {
        items: items,
        nextPageUrl: hasMore ? "more" : null
    };
}
