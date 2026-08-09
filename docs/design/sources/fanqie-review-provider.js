/**
 * RFC-004 real review provider — 番茄小说（书评章评桶）
 *
 * Uses community mirror http://101.35.133.34:5000 (search/detail/directory/content/comment).
 * True paragraph 段评 needs ByteDance signed APIs (unidbg); this source maps **book comments**
 * to chapter-bucket (paraIndex=-1) so overlay can show real comments without signature stack.
 *
 * Bind as 段评源 only. Mirror host may change — update COMMUNITY_API if dead.
 */
var COMMUNITY_API = "http://101.35.133.34:5000";

var config = {
    bookSourceUrl: "https://fanqienovel.com#rfc004-review",
    bookSourceName: "番茄书评(段评源)",
    bookSourceType: 0,
    bookSourceGroup: "段评源",
    bookSourceComment: "RFC-004. Book-level comments as chapter-bucket via community mirror COMMUNITY_API (may die; update host). True Fanqie 段评 needs unidbg.",
    enabledCookieJar: true,
    loginUrl: "https://fanqienovel.com/",
    header: JSON.stringify({
        "User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
        "Referer": "https://fanqienovel.com/"
    }),
    loginUi: [],
    exploreUrl: [],
    lastUpdateTime: 1786243000000
};

function apiGet(pathAndQuery) {
    var raw = String(java.ajax(COMMUNITY_API + pathAndQuery) || "");
    var json;
    try {
        json = JSON.parse(raw);
    } catch (e) {
        throw "番茄社区接口非 JSON: " + raw.slice(0, 120);
    }
    if (json.code && Number(json.code) !== 200 && Number(json.code) !== 0) {
        throw "番茄社区接口失败: " + (json.message || json.msg || json.code);
    }
    return json.data != null ? json.data : json;
}

function bookIdFromUrl(url) {
    var s = String(url || "");
    var m = s.match(/[?&]bookId=([^&]+)/);
    if (m) return decodeURIComponent(m[1]);
    m = s.match(/\/book\/([^/?#]+)/);
    return m ? decodeURIComponent(m[1]) : "";
}

function itemIdFromUrl(url) {
    var s = String(url || "");
    var m = s.match(/[?&]itemId=([^&]+)/);
    return m ? decodeURIComponent(m[1]) : "";
}

function digBooks(node, out) {
    if (!node) return;
    if (Array.isArray(node)) {
        for (var i = 0; i < node.length; i++) digBooks(node[i], out);
        return;
    }
    if (typeof node !== "object") return;
    if (node.book_id && (node.book_name || node.title)) {
        out.push(node);
        return;
    }
    if (node.book_data) digBooks(node.book_data, out);
    for (var k in node) {
        if (!node.hasOwnProperty(k)) continue;
        if (k === "book_data" || k === "search_tabs" || k === "data" || Array.isArray(node[k]) || (node[k] && typeof node[k] === "object")) {
            digBooks(node[k], out);
        }
    }
}

function search(key, page) {
    if (page > 1) return [];
    var data = apiGet("/api/search?key=" + encodeURIComponent(String(key || "")) + "&offset=0");
    var found = [];
    digBooks(data, found);
    var seen = {};
    var out = [];
    for (var i = 0; i < found.length; i++) {
        var b = found[i];
        var bid = String(b.book_id || "");
        if (!bid || seen[bid]) continue;
        seen[bid] = true;
        out.push({
            name: String(b.book_name || b.title || ""),
            author: String(b.author || b.author_name || ""),
            coverUrl: String(b.thumb_url || b.cover || b.audio_thumb_uri || ""),
            intro: String(b.abstract || b.book_abstract || ""),
            bookUrl: "https://fanqienovel.com/page/" + bid + "?bookId=" + encodeURIComponent(bid),
            latestChapterTitle: String(b.last_chapter_title || "")
        });
        if (out.length >= 15) break;
    }
    return out;
}

function getBookInfo(book) {
    var bid = bookIdFromUrl(book.bookUrl);
    var data = apiGet("/api/detail?book_id=" + encodeURIComponent(bid));
    var d = data.data || data;
    return {
        name: String(d.book_name || d.title || book.name || ""),
        author: String(d.author || d.author_name || book.author || ""),
        intro: String(d.abstract || book.intro || ""),
        coverUrl: String(d.thumb_url || d.cover || book.coverUrl || ""),
        tocUrl: book.bookUrl,
        latestChapterTitle: String(d.last_chapter_title || book.latestChapterTitle || "")
    };
}

function getChapters(book) {
    var bid = bookIdFromUrl(book.bookUrl || book.tocUrl);
    var data = apiGet("/api/directory?book_id=" + encodeURIComponent(bid));
    var lists = (data.lists || (data.data && data.data.lists) || []);
    return lists.map(function (c) {
        return {
            title: String(c.title || ""),
            url: "https://fanqienovel.com/reader/" + c.item_id +
                "?bookId=" + encodeURIComponent(bid) +
                "&itemId=" + encodeURIComponent(String(c.item_id || ""))
        };
    }).filter(function (c) { return c.title && itemIdFromUrl(c.url); });
}

function getContent(chapter, book, nextChapterUrl) {
    var itemId = itemIdFromUrl(chapter.url);
    var data = apiGet("/api/content?item_id=" + encodeURIComponent(itemId) + "&tab=" + encodeURIComponent("小说"));
    var content = "";
    if (typeof data === "string") content = data;
    else content = String((data && data.content) || (data && data.data && data.data.content) || "");
    if (!content) return "（正文不可用；书评章评桶仍可加载）";
    return content;
}

function getReviewSummary(chapter, book) {
    var bid = bookIdFromUrl(chapter.url) || bookIdFromUrl(book.bookUrl);
    var data = apiGet("/api/comment?book_id=" + encodeURIComponent(bid) + "&offset=0&count=20");
    var inner = data.data || data;
    var list = (inner && inner.comment) || (inner && inner.data && inner.data.comment) || [];
    var count = Array.isArray(list) ? list.length : 0;
    // Prefer server total if present later; page-1 length is a lower bound signal for bucket.
    if (count <= 0) return [];
    return [{
        paraIndex: -1,
        count: count,
        paraData: "book:" + bid
    }];
}

function getReviewDetail(chapter, book, paraIndex, paraData, page) {
    if (Number(paraIndex) !== -1 && Number(paraIndex) !== 0) {
        return { items: [], nextPageUrl: null };
    }
    var bid = bookIdFromUrl(chapter.url) || bookIdFromUrl(book.bookUrl);
    var pageNum = Number(page || 1);
    if (pageNum < 1) pageNum = 1;
    var pageSize = 20;
    var offset = (pageNum - 1) * pageSize;
    var data = apiGet("/api/comment?book_id=" + encodeURIComponent(bid) +
        "&offset=" + offset + "&count=" + pageSize);
    var inner = data.data || data;
    var list = (inner && inner.comment) || (inner && inner.data && inner.data.comment) || [];
    var items = (list || []).map(function (c) {
        var ui = c.user_info || {};
        var replies = (c.reply_list || c.reply_comment || []).slice(0, 5).map(function (r) {
            var rui = r.user_info || {};
            return {
                id: String(r.comment_id || ""),
                name: String(rui.user_name || rui.nick_name || r.user_name || "读者"),
                avatar: String(rui.user_avatar || rui.avatar_url || ""),
                content: String(r.text || r.content || "")
            };
        });
        return {
            id: String(c.comment_id || ""),
            name: String(ui.user_name || ui.nick_name || c.user_name || "读者"),
            avatar: String(ui.user_avatar || ui.avatar_url || ""),
            badge: c.has_author_digg ? "作者赞" : "",
            content: String(c.text || c.content || ""),
            replies: replies
        };
    }).filter(function (it) { return it.content; });
    return {
        items: items,
        // Page-2 (offset) not device-verified; avoid false has-more.
        nextPageUrl: null
    };
}
