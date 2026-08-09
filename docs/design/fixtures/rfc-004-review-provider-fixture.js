/**
 * RFC-004 device-test fixture: review-capable JS source with offline chapter-bucket reviews.
 *
 * Import via 书源 → 本地导入 / 新建 JS 源 paste.
 * search(key) echoes the query as a book so any shelf title can bind as provider.
 * getReviewSummary always returns paraIndex=-1 (章评) with 2 items — P1 chapter-bucket path.
 * Paragraph reviews intentionally omitted until P2 authority is verified for a real site.
 */
var config = {
    bookSourceUrl: "legado-fixture://review-overlay",
    bookSourceName: "RFC004段评提供方(夹具)",
    bookSourceType: 0,
    bookSourceGroup: "fixture",
    bookSourceComment: "Offline review provider for RFC-004 overlay tests. Not a real novel site.",
    loginUi: [],
    exploreUrl: [],
    lastUpdateTime: 0
};

function search(key, page) {
    if (page > 1) return [];
    var name = String(key || "").trim();
    if (!name) return [];
    return [{
        name: name,
        author: "夹具作者",
        bookUrl: config.bookSourceUrl + "/book?name=" + encodeURIComponent(name),
        intro: "RFC-004 overlay fixture book"
    }];
}

function getBookInfo(book) {
    return {
        name: book.name,
        author: book.author || "夹具作者",
        intro: "RFC-004 overlay fixture",
        tocUrl: book.bookUrl,
        latestChapterTitle: "第一章 开端"
    };
}

function getChapters(book) {
    // Wide enough TOC so ChangeChapterVerify.alignResult can match common titles.
    var chapters = [];
    for (var i = 1; i <= 50; i++) {
        chapters.push({
            title: "第" + i + "章",
            url: book.bookUrl + "/chapter/" + i
        });
    }
    chapters[0].title = "第一章 开端";
    chapters[1].title = "第二章 成长";
    chapters[2].title = "第三章 结局";
    return chapters;
}

function getContent(chapter, book, nextChapterUrl) {
    return "（夹具正文）" + chapter.title + "\n\n本源仅用于段评提供方测试，请用其它源阅读正文。";
}

function getReviewSummary(chapter, book) {
    return [{
        paraIndex: -1,
        count: 2,
        paraData: "chapter:" + String(chapter.title || "")
    }];
}

function getReviewDetail(chapter, book, paraIndex, paraData, page) {
    if (page > 1) {
        return { items: [], nextPageUrl: null };
    }
    if (paraIndex != -1) {
        // P1: paragraph reviews not provided by this fixture.
        return { items: [], nextPageUrl: null };
    }
    return {
        items: [
            {
                id: "f1",
                name: "夹具用户甲",
                content: "章评夹具：针对「" + (chapter.title || "") + "」",
                replies: []
            },
            {
                id: "f2",
                name: "夹具用户乙",
                content: "paraData=" + String(paraData || ""),
                replies: []
            }
        ],
        nextPageUrl: null
    };
}
