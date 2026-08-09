/**
 * RFC-004 device-test fixture: review-capable JS source with offline chapter + paragraph reviews.
 *
 * Import via 书源 → 本地导入 / 新建 JS 源 paste.
 * search(key) echoes the query as a book so any shelf title can bind as provider.
 * Authority: ContentSplitVerified for `legado-fixture://review-overlay` (P2 hard-map).
 * getContent paragraphs are blank-line split; getReviewSummary paraIndex 1..N matches that order.
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

function fixtureParagraphs(chapter) {
    var title = String(chapter && chapter.title || "未知章节");
    return [
        "晨光刚落在城墙上，" + title + "的风便带着尘土扑面而来。巡逻的士兵交换着眼神，谁也不敢先开口。",
        "驿站里传来马蹄声，信使把一卷密封文书交到守将手里。蜡印鲜红，边角还沾着雨痕。",
        "文书展开后，厅内一时静得只剩灯芯爆裂。有人低声说：北境三日内必须回信，否则粮道将断。",
        "夜色压下来时，城门仍未关闭。远处火把连成一线，像一条缓慢游动的金色蛇。"
    ];
}

function getContent(chapter, book, nextChapterUrl) {
    return fixtureParagraphs(chapter).join("\n\n");
}

function getReviewSummary(chapter, book) {
    var paras = fixtureParagraphs(chapter);
    var items = [{
        paraIndex: -1,
        count: 2,
        paraData: "chapter:" + String(chapter.title || "")
    }];
    for (var i = 0; i < paras.length; i++) {
        items.push({
            paraIndex: i + 1,
            count: i === 1 ? 3 : 1,
            paraData: "para:" + (i + 1) + ":" + String(chapter.title || "")
        });
    }
    return items;
}

function getReviewDetail(chapter, book, paraIndex, paraData, page) {
    if (page > 1) {
        return { items: [], nextPageUrl: null };
    }
    if (paraIndex == -1) {
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
    if (paraIndex > 0) {
        return {
            items: [
                {
                    id: "p" + paraIndex + "-1",
                    name: "段评用户",
                    content: "段落" + paraIndex + "评论：" + String(paraData || ""),
                    replies: []
                }
            ],
            nextPageUrl: null
        };
    }
    return { items: [], nextPageUrl: null };
}
