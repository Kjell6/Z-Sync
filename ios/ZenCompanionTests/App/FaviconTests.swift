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

    func testFallsBackToDuckDuckGoHost() {
        let url = FaviconResolver.url(pageURL: "https://news.ycombinator.com/item?id=1", directURL: nil)
        XCTAssertEqual(url?.absoluteString, "https://icons.duckduckgo.com/ip3/news.ycombinator.com.ico")
    }

    func testIgnoresNonHTTPDirectURL() {
        let url = FaviconResolver.url(
            pageURL: "https://example.com",
            directURL: "chrome://branding/content/icon.png"
        )
        XCTAssertEqual(url?.absoluteString, "https://icons.duckduckgo.com/ip3/example.com.ico")
    }

    func testNilWhenPageURLHasNoHost() {
        XCTAssertNil(FaviconResolver.url(pageURL: "not a url", directURL: nil))
        XCTAssertNil(FaviconResolver.url(pageURL: "", directURL: nil))
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

        let urls = Set(FaviconResolver.urls(in: snapshot).map(\.absoluteString))
        XCTAssertEqual(urls, [
            "https://icons.duckduckgo.com/ip3/example.com.ico",
            "https://cdn.example/d.png",
            "https://icons.duckduckgo.com/ip3/folder.example.ico",
            "https://icons.duckduckgo.com/ip3/essential.example.ico",
            "https://icons.duckduckgo.com/ip3/split.example.ico"
        ])
        XCTAssertFalse(urls.contains { $0.contains("static.example") })
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
