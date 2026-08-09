/**
 * RFC-004 real review provider — 晋江文学城（章评）
 *
 * - Info/TOC: app.jjwxc.net androidapi (novelbasicinfo / chapterList)
 * - Search: web search is flaky; numeric novelId works; keyword scrapes free library filter page
 * - Reviews: www.jjwxc.net/comment.php HTML chapter comments → chapter-bucket (paraIndex=-1)
 *
 * Paragraph 段评 needs App login/API; not implemented here.
 * Bind as 段评源 only.
 */
var config = {
    bookSourceUrl: "https://www.jjwxc.net#rfc004-review",
    bookSourceName: "晋江章评(段评源)",
    bookSourceType: 0,
    bookSourceGroup: "段评源",
    bookSourceComment: "RFC-004. Chapter comments from comment.php as chapter-bucket. Search prefers novelId.",
    enabledCookieJar: true,
    loginUrl: "https://www.jjwxc.net/",
    header: JSON.stringify({
        "User-Agent": "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36",
        "Referer": "http://android.jjwxc.net/"
    }),
    loginUi: [],
    exploreUrl: [],
    lastUpdateTime: 1786243000000
};

function appGet(pathAndQuery) {
    var raw = String(java.ajax("https://app.jjwxc.net/androidapi/" + pathAndQuery) || "");
    var json;
    try {
        json = JSON.parse(raw);
    } catch (e) {
        throw "晋江接口非 JSON: " + raw.slice(0, 120);
    }
    if (json.code && String(json.code) !== "0" && json.message && !json.novelId && !json.chapterlist) {
        throw "晋江接口失败: " + json.message;
    }
    return json;
}

function novelIdFromUrl(url) {
    var s = String(url || "");
    var m = s.match(/[?&]novel[Ii]d=(\d+)/);
    if (m) return m[1];
    m = s.match(/\/novel\/(\d+)/);
    return m ? m[1] : "";
}

function chapterIdFromUrl(url) {
    var s = String(url || "");
    var m = s.match(/[?&]chapter[Ii]d=(\d+)/);
    return m ? m[1] : "1";
}

function search(key, page) {
    if (page > 1) return [];
    var q = String(key || "").trim();
    if (!q) return [];
    // Direct novelId
    if (/^\d{4,}$/.test(q)) {
        try {
            var info = appGet("novelbasicinfo?novelId=" + encodeURIComponent(q));
            if (info.novelId) {
                return [{
                    name: String(info.novelName || q),
                    author: String(info.authorName || ""),
                    coverUrl: String(info.novelCover || ""),
                    intro: String(info.novelIntro || "").replace(/&lt;br\/?&gt;/g, "\n"),
                    bookUrl: "https://www.jjwxc.net/onebook.php?novelid=" + info.novelId
                }];
            }
        } catch (e) { /* fall through */ }
    }
    // Free-library keyword scrape (android search often returns 1055 without app sign)
    var html = String(java.ajax(
        "https://www.jjwxc.net/bookbase_slave.php?booktype=free&opt=&order=&page=1"
    ) || "");
    var out = [];
    var re = /onebook\.php\?novelid=(\d+)[^>]*>\s*([^<]{1,60})</g;
    var m;
    var qLower = q.toLowerCase();
    while ((m = re.exec(html)) && out.length < 20) {
        var name = String(m[2] || "").replace(/^\s+|\s+$/g, "");
        if (!name) continue;
        if (name.toLowerCase().indexOf(qLower) < 0 && q.length > 1) continue;
        out.push({
            name: name,
            author: "",
            bookUrl: "https://www.jjwxc.net/onebook.php?novelid=" + m[1]
        });
    }
    // Soft miss: do NOT dump unrelated free-library books (would bind wrong 章评).
    return out;
}

function getBookInfo(book) {
    var nid = novelIdFromUrl(book.bookUrl);
    var info = appGet("novelbasicinfo?novelId=" + encodeURIComponent(nid));
    return {
        name: String(info.novelName || book.name || ""),
        author: String(info.authorName || book.author || ""),
        intro: String(info.novelIntro || "").replace(/&lt;br\/?&gt;/g, "\n"),
        coverUrl: String(info.novelCover || ""),
        tocUrl: book.bookUrl,
        latestChapterTitle: ""
    };
}

function getChapters(book) {
    var nid = novelIdFromUrl(book.bookUrl || book.tocUrl);
    var data = appGet("chapterList?novelId=" + encodeURIComponent(nid) + "&more=0&whole=1");
    var list = data.chapterlist || [];
    var out = [];
    for (var i = 0; i < list.length; i++) {
        var c = list[i];
        if (!c || c.chaptertype === "23") continue; // volume markers often type 23
        var cid = String(c.chapterid || "");
        var title = String(c.chaptername || "");
        if (!cid || !title) continue;
        out.push({
            title: title,
            url: "https://www.jjwxc.net/onebook.php?novelid=" + nid + "&chapterid=" + cid
        });
    }
    return out;
}

function getContent(chapter, book, nextChapterUrl) {
    // Free chapter content via android API when possible; VIP needs token.
    var nid = novelIdFromUrl(chapter.url) || novelIdFromUrl(book.bookUrl);
    var cid = chapterIdFromUrl(chapter.url);
    try {
        var raw = String(java.ajax(
            "https://app.jjwxc.net/androidapi/chapterContent?novelId=" +
            encodeURIComponent(nid) + "&chapterId=" + encodeURIComponent(cid)
        ) || "");
        var json = JSON.parse(raw);
        if (json.content) {
            return String(json.content).replace(/&lt;br\/?&gt;/g, "\n").replace(/<br\s*\/?>/gi, "\n");
        }
        if (json.message) return "（" + json.message + "；章评仍可加载）";
    } catch (e) { /* ignore */ }
    return "（正文需登录/购买时可能为空；章评仍可从 comment.php 加载）";
}

function parseCommentHtml(html) {
    var s = String(html || "");
    var items = [];
    // Prefer body span: <span id='mormalcomment_N'>…</span> (typo "mormal" is upstream).
    var reBody = /id=['"]mormalcomment_(\d+)['"]\s*>([\s\S]*?)<\/span>/gi;
    var m;
    while ((m = reBody.exec(s)) && items.length < 40) {
        var id = "comment_" + m[1];
        var content = String(m[2] || "")
            .replace(/<br\s*\/?>/gi, "\n")
            .replace(/<[^>]+>/g, "")
            .replace(/&nbsp;/g, " ")
            .replace(/^\s+|\s+$/g, "");
        if (!content) continue;
        var name = "书友";
        // Name sits in the preceding tdtitle for this comment id.
        var head = s.slice(Math.max(0, m.index - 1200), m.index);
        var nm = head.match(/网友：[\s\S]{0,200}?<a[^>]*>([^<]{1,40})<\/a>/i);
        if (nm) name = nm[1].replace(/^\s+|\s+$/g, "");
        else if (/作者评论/.test(head)) name = "作者";
        items.push({
            id: id,
            name: name || "书友",
            content: content.slice(0, 800),
            replies: []
        });
    }
    if (items.length) return items;
    // Fallback: count markers only (caller may use length as summary).
    return [];
}

function getReviewSummary(chapter, book) {
    var nid = novelIdFromUrl(chapter.url) || novelIdFromUrl(book.bookUrl);
    var cid = chapterIdFromUrl(chapter.url);
    var html = String(java.ajax(
        "https://www.jjwxc.net/comment.php?novelid=" + encodeURIComponent(nid) +
        "&chapterid=" + encodeURIComponent(cid) + "&page=1"
    ) || "");
    var items = parseCommentHtml(html);
    if (!items.length) {
        // Fallback: count same markers as primary parser
        var n = (html.match(/id=['"]mormalcomment_\d+['"]/gi) || []).length;
        if (n <= 0) return [];
        return [{ paraIndex: -1, count: n, paraData: "jj:" + nid + ":" + cid }];
    }
    return [{
        paraIndex: -1,
        count: items.length,
        paraData: "jj:" + nid + ":" + cid
    }];
}

function getReviewDetail(chapter, book, paraIndex, paraData, page) {
    if (Number(paraIndex) !== -1 && Number(paraIndex) !== 0) {
        return { items: [], nextPageUrl: null };
    }
    var nid = novelIdFromUrl(chapter.url) || novelIdFromUrl(book.bookUrl);
    var cid = chapterIdFromUrl(chapter.url);
    var pageNum = Number(page || 1);
    if (pageNum < 1) pageNum = 1;
    var html = String(java.ajax(
        "https://www.jjwxc.net/comment.php?novelid=" + encodeURIComponent(nid) +
        "&chapterid=" + encodeURIComponent(cid) + "&page=" + pageNum
    ) || "");
    var items = parseCommentHtml(html);
    return {
        items: items,
        // Page-2 not device-verified; avoid infinite/duplicate load.
        nextPageUrl: null
    };
}
