import XCTest
@testable import ZenCompanion

/// Covers the icon payloads Zen syncs for tabs and folders
/// (`ZenEmojiPicker.mjs`, `ZenSpacesSyncModel.sys.mjs`).
final class ZenIconDecoderTests: XCTestCase {
    /// Byte-for-byte the SVG `ZenEmojiPicker.#selectEmoji(emojiAsSVG:)` builds.
    private func emojiDataURL(_ emoji: String) -> String {
        let svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 32 32\"><text y=\"28\" font-size=\"28\" x=\"0\">\(emoji)</text></svg>"
        let base64 = Data(svg.utf8).base64EncodedString()
        return "data:image/svg+xml;base64,\(base64)"
    }

    func testExtractsEmojiFromZenSVGDataURL() {
        XCTAssertEqual(ZenIconDecoder.emoji(fromDataURL: emojiDataURL("📁")), "📁")
        XCTAssertEqual(ZenIconDecoder.emoji(fromDataURL: emojiDataURL("🚀")), "🚀")
        // Multi-codepoint emoji must survive too (ZWJ sequence).
        XCTAssertEqual(ZenIconDecoder.emoji(fromDataURL: emojiDataURL("👨‍👩‍👧‍👦")), "👨‍👩‍👧‍👦")
    }

    func testUnescapesXMLText() {
        XCTAssertEqual(ZenIconDecoder.emoji(fromDataURL: emojiDataURL("a&amp;b")), "a&b")
    }

    func testIgnoresNonEmojiIcons() {
        XCTAssertNil(ZenIconDecoder.emoji(fromDataURL: "chrome://browser/skin/zen-icons/selectable/baseball.svg"))
        XCTAssertNil(ZenIconDecoder.emoji(fromDataURL: "📁"))
        XCTAssertNil(ZenIconDecoder.emoji(fromDataURL: "data:image/png;base64,iVBORw0KGgo="))
        XCTAssertNil(ZenIconDecoder.emoji(fromDataURL: ""))
    }

    /// Live Zen sidebar payload: `viewBox="0 0 100 100"` and `y=".9em"`, not the picker SVG.
    func testExtractsEmojiFromLiveSidebarSVG() {
        let svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\"><text y=\".9em\" font-size=\"90\">✨</text></svg>"
        let url = "data:image/svg+xml;base64,\(Data(svg.utf8).base64EncodedString())"
        XCTAssertEqual(ZenIconDecoder.emoji(fromDataURL: url), "✨")
    }

    func testExtractsEmojiWhenCharsetPrecedesBase64() {
        let svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 32 32\"><text y=\"28\" font-size=\"28\">🚀</text></svg>"
        let url = "data:image/svg+xml;charset=utf-8;base64,\(Data(svg.utf8).base64EncodedString())"
        XCTAssertEqual(ZenIconDecoder.emoji(fromDataURL: url), "🚀")
    }

    func testExtractsEmojiFromPercentEncodedSVG() {
        let svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 32 32\"><text y=\"28\">🚀</text></svg>"
        let encoded = svg.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""
        XCTAssertEqual(ZenIconDecoder.emoji(fromDataURL: "data:image/svg+xml,\(encoded)"), "🚀")
    }

    func testExtractsRasterNestedInSVG() {
        let png = Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")!
        let svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"><image xlink:href=\"data:image/png;base64,\(png.base64EncodedString())\"/></svg>"
        let nested = ZenIconDecoder.embeddedRaster(in: Data(svg.utf8))
        XCTAssertEqual(nested, png)
    }

    func testFolderFlapSkipsPlainFolderEmoji() {
        XCTAssertNil(ZenIconDecoder.folderFlapIcon(nil))
        XCTAssertNil(ZenIconDecoder.folderFlapIcon(""))
        XCTAssertNil(ZenIconDecoder.folderFlapIcon("   "))
        XCTAssertNil(ZenIconDecoder.folderFlapIcon("📁"))
        XCTAssertNil(ZenIconDecoder.folderFlapIcon("📂"))
        XCTAssertNil(ZenIconDecoder.folderFlapIcon(emojiDataURL("📁")))
        XCTAssertNil(ZenIconDecoder.folderFlapIcon(emojiDataURL("📂")))
        let rocket = "chrome://browser/skin/zen-icons/selectable/rocket.svg"
        XCTAssertEqual(ZenIconDecoder.folderFlapIcon(rocket), rocket)
        XCTAssertEqual(ZenIconDecoder.folderFlapIcon(emojiDataURL("🚀")), emojiDataURL("🚀"))
        XCTAssertEqual(ZenIconDecoder.folderFlapIcon("ticket"), "ticket")
    }

    func testExtractsDataURLBytes() {
        let png = Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")!
        let base64URL = "data:image/png;base64,\(png.base64EncodedString())"
        XCTAssertEqual(ZenIconDecoder.dataURLBytes(base64URL), png)
        XCTAssertNotNil(ZenIconDecoder.dataURLBytes("data:image/svg+xml,%3Csvg%3E%3C/svg%3E"))
        XCTAssertNil(ZenIconDecoder.dataURLBytes("chrome://browser/skin/zen-icons/selectable/baseball.svg"))
        XCTAssertNil(ZenIconDecoder.dataURLBytes("data:image/png;base64"))
    }
}

final class ZenIconAssetTests: XCTestCase {
    func testAssetNameForSelectableChromeURL() {
        XCTAssertEqual(
            ZenIconView.assetName(for: "chrome://browser/skin/zen-icons/selectable/baseball.svg"),
            "zen-baseball"
        )
        XCTAssertEqual(
            ZenIconView.assetName(for: "chrome://browser/skin/zen-icons/selectable/globe-1.svg"),
            "zen-globe-1"
        )
    }

    func testAssetNameForBareNamesAndFiles() {
        XCTAssertEqual(ZenIconView.assetName(for: "baseball"), "zen-baseball")
        XCTAssertEqual(ZenIconView.assetName(for: "baseball.svg"), "zen-baseball")
        XCTAssertEqual(ZenIconView.assetName(for: "baseball.png"), "zen-baseball")
        XCTAssertNil(ZenIconView.assetName(for: "   "))
    }
}
