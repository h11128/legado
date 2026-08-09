/**
 * RFC-004 real review provider — QQ阅读（书吧书评）
 *
 * Web has no stable public 段评/章评 XHR (commontgw 404 / App-only).
 * This source scrapes SSR HTML:
 *   - search: /book-search/{key}  → a[title][href*=book-detail]
 *   - reviews: /book-comment/{bid} → li.reply-list (book-level 书吧)
 *
 * Maps book comments to chapter-bucket (paraIndex=-1). Not true paragraph 段评.
 * Bind as 段评源 only.
 */
var config = {
    bookSourceUrl: "https://book.qq.com#rfc004-review",
    bookSourceName: "QQ阅读书吧(段评源)",
    bookSourceType: 0,
    bookSourceGroup: "段评源",
    bookSourceComment: "RFC-004. Book-circle comments as chapter-bucket. True 段评 needs App capture.",
    enabledCookieJar: true,
    loginUrl: "https://book.qq.com/",
    header: JSON.stringify({
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer": "https://book.qq.com/"
    }),
    loginUi: [],
    exploreUrl: [],
    lastUpdateTime: 1786244000000
};

function bidFromUrl(url) {
    var s = String(url || "");
    var m = s.match(/book-(?:detail|comment|read)\/(\d+)/);
    if (m) return m[1];
    m = s.match(/[?&]bid=(\d+)/);
    return m ? m[1] : "";
}

function search(key, page) {
    if (page > 1) return [];
    var q = String(key || "").trim();
    if (!q) return [];
    // Direct bid or pasted detail/comment/read URL (bind UX when keyword search 404s).
    var directBid = bidFromUrl(q);
    if (!directBid && /^\d{4,}$/.test(q)) directBid = q;
    if (directBid) {
        var info = getBookInfo({ bookUrl: "https://book.qq.com/book-detail/" + directBid, name: "" });
        return [{
            name: info.name || ("QQ书" + directBid),
            author: info.author || "",
            bookUrl: "https://book.qq.com/book-detail/" + directBid
        }];
    }
    var html = String(java.ajax("https://book.qq.com/book-search/" + encodeURIComponent(q)) || "");
    // Soft-404 homepage has no book-detail cards.
    if (html.indexOf("book-detail/") < 0) return [];
    var out = [];
    var seen = {};
    var re = /<a[^>]*title="([^"]{1,80})"[^>]*href="[^"]*book-detail\/(\d+)"/gi;
    var m;
    while ((m = re.exec(html)) && out.length < 15) {
        var name = m[1];
        var bid = m[2];
        if (!bid || seen[bid]) continue;
        if (/^(会员|免费)$/.test(name)) continue;
        seen[bid] = true;
        out.push({
            name: name,
            author: "",
            bookUrl: "https://book.qq.com/book-detail/" + bid
        });
    }
    // Alternate attribute order: href then title
    if (!out.length) {
        re = /href="[^"]*book-detail\/(\d+)"[^>]*title="([^"]{1,80})"/gi;
        while ((m = re.exec(html)) && out.length < 15) {
            var bid2 = m[1];
            var name2 = m[2];
            if (!bid2 || seen[bid2] || /^(会员|免费)$/.test(name2)) continue;
            seen[bid2] = true;
            out.push({
                name: name2,
                author: "",
                bookUrl: "https://book.qq.com/book-detail/" + bid2
            });
        }
    }
    return out;
}

function getBookInfo(book) {
    var bid = bidFromUrl(book.bookUrl);
    var html = String(java.ajax("https://book.qq.com/book-detail/" + bid) || "");
    var name = book.name || "";
    var nm = html.match(/<h1[^>]*>([^<]{1,80})<\/h1>/i) ||
        html.match(/property="og:title"\s+content="([^"]+)"/i) ||
        html.match(/<title>([^_<]+)/i);
    if (nm) name = nm[1].replace(/^\s+|\s+$/g, "");
    var author = "";
    var am = html.match(/作者[:：]\s*([^<\n]{1,40})/);
    if (am) author = am[1].replace(/^\s+|\s+$/g, "");
    return {
        name: name || book.name || ("书" + bid),
        author: author || book.author || "",
        intro: "",
        coverUrl: book.coverUrl || "",
        tocUrl: book.bookUrl,
        latestChapterTitle: ""
    };
}

function getChapters(book) {
    var bid = bidFromUrl(book.bookUrl || book.tocUrl);
    // Book-level 书吧 maps to every chapter as the same bucket; expose a small catalog
    // so overlay chapter-align can still pick a chapter title when present.
    var html = String(java.ajax("https://book.qq.com/book-detail/" + bid) || "");
    var out = [];
    var seen = {};
    var re = /href="[^"]*book-read\/(\d+)\/(\d+)"[^>]*>([\s\S]*?)<\/a>/gi;
    var m;
    while ((m = re.exec(html)) && out.length < 80) {
        if (m[1] !== bid) continue;
        var cid = m[2];
        if (seen[cid]) continue;
        var title = String(m[3] || "").replace(/<[^>]+>/g, "").replace(/^\s+|\s+$/g, "");
        if (!title || /开始阅读|新书《/.test(title)) continue;
        seen[cid] = true;
        out.push({
            title: title,
            url: "https://book.qq.com/book-read/" + bid + "/" + cid + "?bid=" + bid
        });
    }
    if (!out.length) {
        out.push({
            title: "全书书评",
            url: "https://book.qq.com/book-comment/" + bid + "?bid=" + bid
        });
    }
    return out;
}

function getContent(chapter, book, nextChapterUrl) {
    return "（QQ阅读段评源不提供正文；请用其他源作内容源，本书源仅作段评提供方）";
}

function parseReplyList(html) {
    var s = String(html || "");
    var items = [];
    var re = /<li[^>]*class="[^"]*reply-list[^"]*"[^>]*>([\s\S]*?)<\/li>/gi;
    var m;
    while ((m = re.exec(s)) && items.length < 40) {
        var block = m[1];
        var name = "";
        var nm = block.match(/class="name"[^>]*>\s*<span[^>]*>([^<]{1,40})<\/span>/i) ||
            block.match(/class="name"[^>]*>([^<]{1,40})</i);
        if (nm) name = nm[1].replace(/^\s+|\s+$/g, "");
        var text = block
            .replace(/<script[\s\S]*?<\/script>/gi, "")
            .replace(/<style[\s\S]*?<\/style>/gi, "")
            .replace(/<br\s*\/?>/gi, "\n")
            .replace(/<[^>]+>/g, "\n")
            .replace(/&nbsp;/g, " ")
            .replace(/\r/g, "");
        var lines = text.split("\n").map(function (x) {
            return x.replace(/^\s+|\s+$/g, "");
        }).filter(Boolean);
        // Typical: name, datetime, content..., 回复, 赞
        var contentLines = [];
        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            if (!name && i === 0) {
                name = line;
                continue;
            }
            if (name && line === name) continue;
            if (/^\d{4}-\d{2}-\d{2}/.test(line)) continue;
            if (/^(回复|赞|\d+)$/.test(line)) continue;
            contentLines.push(line);
        }
        var content = contentLines.join("\n").replace(/^\s+|\s+$/g, "");
        if (!content) continue;
        items.push({
            id: "qq-" + items.length + "-" + (name || "anon"),
            name: name || "书友",
            content: content.slice(0, 800),
            replies: []
        });
    }
    return items;
}

function commentPageUrl(bid, page) {
    var pageNum = Number(page || 1);
    if (pageNum <= 1) return "https://book.qq.com/book-comment/" + bid;
    // Pagination uses queryIndex cursors; without API we only reliably have page 1.
    // Returning page-1 URL keeps detail non-empty rather than inventing cursors.
    return "https://book.qq.com/book-comment/" + bid;
}

function getReviewSummary(chapter, book) {
    var bid = bidFromUrl(chapter.url) || bidFromUrl(book.bookUrl);
    if (!bid) return [];
    var html = String(java.ajax(commentPageUrl(bid, 1)) || "");
    var items = parseReplyList(html);
    var n = items.length;
    if (n <= 0) {
        n = (html.match(/class="[^"]*reply-list/g) || []).length;
    }
    if (n <= 0) return [];
    return [{
        paraIndex: -1,
        count: n,
        paraData: "qq:" + bid
    }];
}

function getReviewDetail(chapter, book, paraIndex, paraData, page) {
    if (Number(paraIndex) !== -1 && Number(paraIndex) !== 0) {
        return { items: [], nextPageUrl: null };
    }
    var bid = bidFromUrl(chapter.url) || bidFromUrl(book.bookUrl);
    if (!bid && paraData) {
        var m = String(paraData).match(/qq:(\d+)/);
        if (m) bid = m[1];
    }
    var html = String(java.ajax(commentPageUrl(bid, page)) || "");
    var items = parseReplyList(html);
    return {
        items: items,
        // Honest: cursor pagination (queryIndex) not implemented; page-1 only.
        nextPageUrl: null
    };
}
