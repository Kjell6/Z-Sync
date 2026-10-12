import XCTest
@testable import ZenCompanion

final class FaviconResolverTests: XCTestCase {
    func testDirectHTTPSURLWins() {
        let url = FaviconResolver.url(
            pageURL: "https://example.com/path",
            directURL: "https://cdn.example.com/fav.png"
        )
        XCTAssertEqual(url?.absoluteString, "https://cdn.example.com/fav.png")
    }

    func testDirectHTTPURLWins() {
        let url = FaviconResolver.url(
            pageURL: "https://example.com",
            directURL: "http://example.com/favicon.ico"
        )
        XCTAssertEqual(url?.absoluteString, "http://example.com/favicon.ico")
    }

    func testSyncedURLDoesNotUseAnIconService() {
        XCTAssertNil(FaviconResolver.url(pageURL: "https://news.ycombinator.com/item?id=1", directURL: nil))
        let document = FaviconDiscovery.documentURL(from: "https://user:secret@news.ycombinator.com/item?id=1#c")
        XCTAssertEqual(document?.absoluteString, "https://news.ycombinator.com/item")
        let urls = FaviconDiscovery.candidates(html: nil, finalURL: nil, pageURL: document!).map(\.absoluteString)
        XCTAssertEqual(urls, ["https://news.ycombinator.com/favicon.ico"])
        XCTAssertFalse(urls.contains { $0.contains("duckduckgo") || $0.contains("google.com/s2") })
    }

    func testIgnoresNonHTTPDirectURL() {
        XCTAssertNil(FaviconResolver.url(
            pageURL: "https://example.com",
            directURL: "chrome://branding/content/icon.png"
        ))
        XCTAssertEqual(
            FaviconResolver.cacheKey(pageURL: "https://example.com", directURL: "chrome://branding/content/icon.png"),
            "site:https://example.com"
        )
    }

    func testNilWhenPageURLHasNoHost() {
        XCTAssertNil(FaviconResolver.url(pageURL: "not a url", directURL: nil))
        XCTAssertNil(FaviconResolver.url(pageURL: "", directURL: nil))
    }

    func testInlineDataIconOverridesRemoteLookup() {
        let data = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        // The synced inline icon wins even when a direct URL is also present.
        XCTAssertEqual(FaviconResolver.usableDataURL(data), data)
        XCTAssertEqual(
            FaviconResolver.cacheKey(
                pageURL: "https://www.figma.com/design/x",
                directURL: "https://cdn.example/d.png",
                dataURL: data
            ),
            "data:https://www.figma.com/design/x"
        )
        // Non-image static icons (emoji, chrome: refs) are not data icons.
        XCTAssertNil(FaviconResolver.usableDataURL("📱"))
        XCTAssertNil(FaviconResolver.usableDataURL("chrome://branding/content/icon.png"))
        XCTAssertNil(FaviconResolver.usableDataURL(nil))
    }

    func testDataIconTabIsRequestedEvenWhenMarkedStatic() {
        let data = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        let dataTab = ZenTab(id: "a", url: "http://localhost:3456/?cols=5", title: "Board", icon: data, hasStaticIcon: false)
        let emojiTab = ZenTab(id: "b", url: "https://emoji.example", title: "Emoji", icon: "📱", hasStaticIcon: true)
        let snapshot = ZenSnapshot(
            spaces: [ZenSpace(id: "s1", name: "Work", pinned: [.tab(dataTab), .tab(emojiTab)])],
            essentials: [:],
            fetchedAt: Date()
        )
        let requests = FaviconResolver.requests(in: snapshot)
        XCTAssertEqual(requests.map(\.cacheKey), ["data:http://localhost:3456/?cols=5"])
        XCTAssertEqual(requests.first?.dataURL, data)
    }

    func testSnapshotCollectsUniqueRemoteIconsAndSkipsStatic() {
        let remote = ZenTab(id: "a", url: "https://example.com", title: "A")
        let duplicate = ZenTab(id: "b", url: "https://example.com/other", title: "B")
        let staticIcon = ZenTab(
            id: "c", url: "https://static.example", title: "C",
            icon: "📱", hasStaticIcon: true
        )
        let withDirect = ZenTab(
            id: "d", url: "https://direct.example", title: "D",
            iconURL: "https://cdn.example/d.png"
        )
        let inFolder = ZenTab(id: "e", url: "https://folder.example", title: "E")
        let essential = ZenTab(id: "f", url: "https://essential.example", title: "F")
        let inSplit = ZenTab(id: "g", url: "https://split.example", title: "G")

        let space = ZenSpace(
            id: "s1",
            name: "Work",
            pinned: [
                .tab(remote),
                .tab(duplicate),
                .tab(staticIcon),
                .tab(withDirect),
                .folder(ZenFolder(id: "f1", name: "Docs", icon: nil, tabs: [inFolder])),
                .split(ZenSplit(id: "sp1", gridType: "vsep", tabs: [inSplit, remote]))
            ]
        )
        let snapshot = ZenSnapshot(
            spaces: [space],
            essentials: ["default": [essential, remote]],
            fetchedAt: Date()
        )

        let keys = Set(FaviconResolver.requests(in: snapshot).map(\.cacheKey))
        XCTAssertEqual(keys, [
            "site:https://example.com",
            "direct:https://cdn.example/d.png",
            "site:https://folder.example",
            "site:https://essential.example",
            "site:https://split.example"
        ])
        XCTAssertFalse(keys.contains { $0.contains("static.example") })
    }
}

final class FaviconDiscoveryTests: XCTestCase {
    func testDeclaredIconsPreferTouchPNGAndKeepSVGLast() {
        let html = """
        <head>
        <base href="https://cdn.example/app/">
        <link rel="mask-icon" href="/mask.svg" color="#000">
        <link rel="icon" type="image/svg+xml" href="icon.svg">
        <link rel="apple-touch-icon" sizes="180x180" href="/touch.png">
        <link rel="shortcut icon" href="//static.example/fav.ico">
        <link rel="icon" href="plain.png" sizes="32x32">
        </head>
        """
        let page = URL(string: "https://ticktick.com/home")!
        let final = URL(string: "https://ticktick.com/home")!
        let urls = FaviconDiscovery.candidates(html: html, finalURL: final, pageURL: page).map(\.absoluteString)
        XCTAssertEqual(urls, [
            "https://cdn.example/touch.png",
            "https://cdn.example/app/plain.png",
            "https://static.example/fav.ico",
            "https://cdn.example/app/icon.svg",
            "https://ticktick.com/favicon.ico"
        ])
    }

    func testSvgIconDetection() {
        XCTAssertTrue(FaviconDiscovery.isSvgIcon(URL(string: "https://variant.com/favicon-light.svg")!))
        XCTAssertTrue(FaviconDiscovery.isSvgIcon(URL(string: "https://x.com/favicon.svg?v=2")!))
        XCTAssertFalse(FaviconDiscovery.isSvgIcon(URL(string: "https://x.com/favicon.ico")!))
        XCTAssertFalse(FaviconDiscovery.isSvgIcon(URL(string: "https://cdn.example/app/plain.png")!))
    }

    func testLoginPageIconsAreIgnoredForCalendar() {
        let html = "<head><link rel=\"icon\" href=\"https://www.google.com/favicon.ico\"></head>"
        let page = URL(string: "https://calendar.google.com/calendar/u/0/r")!
        let final = URL(string: "https://accounts.google.com/v3/signin/identifier")!
        let urls = FaviconDiscovery.candidates(html: html, finalURL: final, pageURL: page).map(\.absoluteString)
        XCTAssertEqual(urls.first, "https://calendar.google.com/googlecalendar/images/favicons_2020q4/calendar_31.ico")
        XCTAssertFalse(urls.contains("https://www.google.com/favicon.ico"))
        XCTAssertTrue(urls.contains("https://calendar.google.com/favicon.ico"))
    }

    func testDocsProductsUseTheirOwnIcon() {
        let sheets = URL(string: "https://docs.google.com/spreadsheets/d/abc")!
        let docs = URL(string: "https://docs.google.com/document/d/abc")!
        XCTAssertEqual(
            FaviconDiscovery.candidates(html: nil, finalURL: nil, pageURL: sheets).first?.absoluteString,
            "https://ssl.gstatic.com/docs/spreadsheets/favicon3.ico"
        )
        XCTAssertEqual(
            FaviconDiscovery.candidates(html: nil, finalURL: nil, pageURL: docs).first?.absoluteString,
            "https://ssl.gstatic.com/docs/documents/images/kix-favicon7.ico"
        )
        XCTAssertEqual(
            FaviconResolver.cacheKey(pageURL: sheets.absoluteString, directURL: nil),
            "site:https://docs.google.com/spreadsheets"
        )
    }

    func testLoopbackIsNotFetched() {
        let local = URL(string: "http://localhost:3000/board")!
        XCTAssertTrue(FaviconDiscovery.isLoopback(local))
        XCTAssertFalse(FaviconDiscovery.isLoopback(URL(string: "https://example.com")!))
    }
}

final class FaviconDecoderTests: XCTestCase {
    func testDecodesPNG() {
        let png = Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")!
        let image = FaviconDecoder.image(from: png)
        XCTAssertNotNil(image)
        XCTAssertEqual(image?.size.width, 1)
        XCTAssertEqual(image?.size.height, 1)
    }

    func testRejectsEmptyAndTinyPayloads() {
        XCTAssertNil(FaviconDecoder.image(from: Data()))
        XCTAssertNil(FaviconDecoder.image(from: Data("nope".utf8)))
    }
}
