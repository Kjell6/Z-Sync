import XCTest

@testable import ZenCompanion

final class PageTitleTests: XCTestCase {
    private let page = URL(string: "https://pin.example/page")!

    func testUsableTitleRejectsTheAddress() {
        XCTAssertEqual(PageTitle.usable("Pinned Page", url: page), "Pinned Page")
        XCTAssertEqual(PageTitle.usable("  Tom   & Jerry  ", url: page), "Tom & Jerry")
        XCTAssertNil(PageTitle.usable("   ", url: page))
        XCTAssertNil(PageTitle.usable("https://pin.example/page", url: page))
        XCTAssertNil(PageTitle.usable("https://pin.example/page/", url: page))
        XCTAssertNil(PageTitle.usable("http://www.pin.example/page", url: page))
        XCTAssertNil(PageTitle.usable("pin.example", url: page))
        XCTAssertNil(PageTitle.usable("www.pin.example", url: page))
        XCTAssertNil(PageTitle.usable("pin.example/page", url: page))
        XCTAssertEqual(PageTitle.usable("Node.js", url: page), "Node.js")
    }

    func testHeadlineFallsBackToHost() {
        XCTAssertEqual(PageTitle.headline("Pinned Page", url: page), "Pinned Page")
        XCTAssertEqual(PageTitle.headline("https://pin.example/page/", url: page), "pin.example")
        XCTAssertEqual(PageTitle.headline("", url: page), "pin.example")
        XCTAssertEqual(PageTitle.headline("", url: nil), "Tab")
    }

    func testSameDocumentIgnoresSlashWwwAndFragment() {
        let base = URL(string: "https://pin.example/page")!
        XCTAssertTrue(PageTitle.sameDocument(base, URL(string: "https://www.pin.example/page/")))
        XCTAssertTrue(PageTitle.sameDocument(base, URL(string: "https://pin.example/page#section")))
        XCTAssertFalse(PageTitle.sameDocument(base, URL(string: "https://other.example/page")))
        XCTAssertFalse(PageTitle.sameDocument(nil, base))
    }

    func testExtractPrefersDocumentTitleThenSocialTitle() {
        let html = """
        <html><head>
        <title>  Tom &amp; Jerry&#39;s  </title>
        <meta property="og:title" content="Ignored">
        </head></html>
        """
        XCTAssertEqual(PageTitle.extract(fromHTML: html, url: page), "Tom & Jerry's")

        let urlShaped = """
        <title>https://pin.example/page</title>
        <meta property="og:title" content="From Open Graph">
        """
        XCTAssertEqual(PageTitle.extract(fromHTML: urlShaped, url: page), "From Open Graph")

        let twitter = """
        <title></title>
        <meta name="twitter:title" content="Tweet &amp; Title">
        """
        XCTAssertEqual(PageTitle.extract(fromHTML: twitter, url: page), "Tweet & Title")
        XCTAssertNil(PageTitle.extract(fromHTML: "<html></html>", url: page))
    }

    func testPickDocumentTitlePrefersTheTabTitle() {
        XCTAssertEqual(PageTitle.pickDocumentTitle("Pinned Page\u{1e}Other", url: page), "Pinned Page")
        XCTAssertEqual(
            PageTitle.pickDocumentTitle("https://pin.example/page\u{1e}From Open Graph", url: page),
            "From Open Graph"
        )
        XCTAssertNil(PageTitle.pickDocumentTitle("https://pin.example/page\u{1e}", url: page))
    }
}
