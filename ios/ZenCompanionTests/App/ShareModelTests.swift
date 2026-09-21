import XCTest

@testable import ZenCompanion

@MainActor
final class ShareModelTests: XCTestCase {
    private func space(_ id: String, folders: [ZenCompanion.ZenFolder] = []) -> ZenCompanion.ZenSpace {
        ZenCompanion.ZenSpace(
            id: id,
            name: id,
            pinned: folders.map { .folder($0) }
        )
    }

    private func snapshot(_ spaces: [ZenCompanion.ZenSpace]) -> ZenCompanion.ZenSnapshot {
        ZenCompanion.ZenSnapshot(spaces: spaces, fetchedAt: Date(timeIntervalSince1970: 0))
    }

    private func makeModel(
        session: FakeShareSession,
        url: URL? = URL(string: "https://example.com"),
        pageTitle: String = "Example"
    ) -> ZenCompanion.ShareModel {
        ZenCompanion.ShareModel(session: session, pageTitle: pageTitle, url: url, sleep: { _ in })
    }

    func testCachedSnapshotStartsInPick() async {
        let space = space("s1")
        let session = FakeShareSession(
            signedIn: true,
            cached: snapshot([space]),
            fresh: snapshot([space])
        )
        let model = makeModel(session: session)

        await model.bootstrap()

        XCTAssertEqual(model.phase, .pick)
        XCTAssertEqual(model.spaces.map(\.id), ["s1"])
        XCTAssertEqual(model.destination, ZenCompanion.PinDestination(spaceId: "s1"))
    }

    func testEmptyFreshSnapshotIsIgnored() async {
        let cached = space("s1")
        let session = FakeShareSession(
            signedIn: true,
            cached: snapshot([cached]),
            fresh: snapshot([])
        )
        let model = makeModel(session: session)

        await model.bootstrap()

        XCTAssertEqual(model.spaces.map(\.id), ["s1"])
        XCTAssertEqual(model.phase, .pick)
    }

    func testVanishedFolderFallsBackToSpaceRoot() async {
        let withFolder = space("s1", folders: [ZenCompanion.ZenFolder(id: "gone", name: "Gone")])
        let session = FakeShareSession(
            signedIn: true,
            cached: snapshot([withFolder]),
            fresh: snapshot([withFolder])
        )
        let model = makeModel(session: session)
        await model.bootstrap()
        model.selectDestination(ZenCompanion.PinDestination(spaceId: "s1", folderId: "gone"))
        XCTAssertEqual(model.destination.folderId, "gone")

        session.fresh = snapshot([space("s1")])
        await model.refreshSpaces()

        XCTAssertEqual(model.destination, ZenCompanion.PinDestination(spaceId: "s1"))
    }

    func testSaveAddsTabAndFinishesAfterSleep() async {
        let space = space("s1")
        let session = FakeShareSession(
            signedIn: true,
            cached: snapshot([space]),
            fresh: snapshot([space])
        )
        let model = makeModel(
            session: session,
            url: URL(string: "https://example.com/page"),
            pageTitle: "Page"
        )
        await model.bootstrap()

        await model.save()

        XCTAssertEqual(model.phase, .saved)
        XCTAssertEqual(session.added.count, 1)
        XCTAssertEqual(session.added[0].url, URL(string: "https://example.com/page"))
        XCTAssertEqual(session.added[0].title, "Page")
        XCTAssertEqual(session.added[0].spaceId, "s1")
        XCTAssertEqual(session.lastSpace, "s1")
        XCTAssertTrue(model.didFinish)
    }

    func testNormalSaveIgnoresFolderAndShowsFallbackNotice() async {
        let space = space("s1", folders: [ZenCompanion.ZenFolder(id: "folder-1", name: "Folder")])
        let session = FakeShareSession(
            signedIn: true,
            cached: snapshot([space]),
            fresh: snapshot([space]),
            saveKind: .normal,
            fallBackToPinned: true
        )
        let model = makeModel(
            session: session,
            url: URL(string: "https://example.com/page"),
            pageTitle: "Page"
        )
        await model.bootstrap()

        XCTAssertTrue(model.hideFolders)
        model.selectDestination(ZenCompanion.PinDestination(spaceId: "s1", folderId: "folder-1"))
        XCTAssertEqual(model.destination, ZenCompanion.PinDestination(spaceId: "s1"))

        await model.save()

        XCTAssertEqual(model.phase, .saved)
        XCTAssertTrue(model.savedAsPinnedFallback)
        XCTAssertEqual(session.added.count, 1)
        XCTAssertEqual(session.added[0].kind, .normal)
        XCTAssertNil(session.added[0].folderId)
        XCTAssertTrue(model.didFinish)
    }

    func testSaveWithNoSpacesFailsWithoutWrite() async {
        let session = FakeShareSession(
            signedIn: true,
            cached: nil,
            fresh: snapshot([])
        )
        let model = makeModel(session: session)
        await model.bootstrap()

        await model.save()

        XCTAssertEqual(model.phase, .failed)
        XCTAssertEqual(model.error, "no spaces")
        XCTAssertTrue(session.added.isEmpty)
        XCTAssertNil(session.lastSpace)
    }

    func testHttpOnlyShareURLs() {
        XCTAssertEqual(
            ZenCompanion.ShareLink.httpURL(from: URL(string: "https://example.com")!),
            URL(string: "https://example.com")
        )
        XCTAssertEqual(
            ZenCompanion.ShareLink.httpURL(from: URL(string: "HTTP://EXAMPLE.COM")!),
            URL(string: "HTTP://EXAMPLE.COM")
        )
        XCTAssertNil(ZenCompanion.ShareLink.httpURL(from: URL(string: "file:///tmp/x")!))
        XCTAssertNil(ZenCompanion.ShareLink.httpURL(from: URL(string: "javascript:alert(1)")!))
        XCTAssertEqual(
            ZenCompanion.ShareLink.httpURL(from: " https://ok.example " as NSString),
            URL(string: "https://ok.example")
        )
    }

    func testHeadlineTitlePrefersPageTitleThenHost() {
        let url = URL(string: "https://pin.example/page")!
        XCTAssertEqual(ZenCompanion.ShareLink.headlineTitle(pageTitle: "Pinned Page", url: url), "Pinned Page")
        XCTAssertEqual(ZenCompanion.ShareLink.headlineTitle(pageTitle: "pin.example", url: url), "pin.example")
        XCTAssertEqual(ZenCompanion.ShareLink.headlineTitle(pageTitle: "  ", url: url), "pin.example")
        XCTAssertEqual(
            ZenCompanion.ShareLink.headlineTitle(pageTitle: "", url: URL(string: "https://example.com/path")),
            "example.com"
        )
    }

    func testSignedOutSkipsRefresh() async {
        let session = FakeShareSession(
            signedIn: false,
            cached: snapshot([space("s1")]),
            fresh: snapshot([space("s1")])
        )
        let model = makeModel(session: session)

        await model.bootstrap()

        XCTAssertEqual(model.phase, .signedOut)
        XCTAssertEqual(session.refreshCount, 0)
        XCTAssertTrue(model.spaces.isEmpty)
    }
}

@MainActor
private final class FakeShareSession: ZenCompanion.ShareSessioning {
    struct Added {
        let url: URL
        let title: String
        let spaceId: String
        let folderId: String?
        let kind: ZenCompanion.SaveKind
    }

    var signedIn: Bool
    var cached: ZenCompanion.ZenSnapshot?
    var fresh: ZenCompanion.ZenSnapshot
    var saveKindValue: ZenCompanion.SaveKind
    var fallBackToPinned: Bool
    var lastSpace: String?
    private(set) var added: [Added] = []
    private(set) var refreshCount = 0

    init(
        signedIn: Bool,
        cached: ZenCompanion.ZenSnapshot?,
        fresh: ZenCompanion.ZenSnapshot,
        saveKind: ZenCompanion.SaveKind = .pinned,
        fallBackToPinned: Bool = false
    ) {
        self.signedIn = signedIn
        self.cached = cached
        self.fresh = fresh
        self.saveKindValue = saveKind
        self.fallBackToPinned = fallBackToPinned
        self.lastSpace = nil
    }

    func isSignedIn() -> Bool { signedIn }
    func cachedSnapshot() -> ZenCompanion.ZenSnapshot? { cached }
    func lastSpaceId() -> String? { lastSpace }
    func setLastSpaceId(_ id: String?) { lastSpace = id }
    func refresh() async throws -> ZenCompanion.ZenSnapshot {
        refreshCount += 1
        return fresh
    }
    func addTab(
        url: URL,
        title: String,
        to spaceId: String,
        folderId: String?,
        kind: ZenCompanion.SaveKind
    ) async throws -> ZenCompanion.AddTabOutcome {
        added.append(Added(url: url, title: title, spaceId: spaceId, folderId: folderId, kind: kind))
        return fallBackToPinned
            ? ZenCompanion.AddTabOutcome.fallback(recordId: "tab-\(added.count)")
            : ZenCompanion.AddTabOutcome(recordId: "tab-\(added.count)", kind: kind, fellBackToPinned: false)
    }
    func errorText(_ error: Error) -> String { error.localizedDescription }
    func noSpacesErrorText() -> String { "no spaces" }
    func saveKind() -> ZenCompanion.SaveKind { saveKindValue }
}
