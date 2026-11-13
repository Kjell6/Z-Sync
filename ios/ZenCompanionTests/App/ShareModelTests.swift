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

    private func snapshot(
        _ spaces: [ZenCompanion.ZenSpace],
        capability: ZenCompanion.NormalTabsCapability = .absent
    ) -> ZenCompanion.ZenSnapshot {
        ZenCompanion.ZenSnapshot(
            spaces: spaces,
            normalTabsCapability: capability,
            fetchedAt: Date(timeIntervalSince1970: 0)
        )
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

    /// No cache and no synced spaces: the sheet explains instead of spinning.
    func testNoCacheAndNoSpacesFailsInsteadOfSpinning() async {
        let session = FakeShareSession(signedIn: true, cached: nil, fresh: snapshot([]))
        let model = makeModel(session: session)

        await model.bootstrap()

        XCTAssertEqual(model.phase, .failed)
        XCTAssertEqual(model.error, "no spaces")
    }

    /// A refresh landing mid-save keeps the sheet in `.saving`, and a second
    /// tap while saving writes nothing.
    func testRefreshAndSecondTapDuringSaveDoNotSaveTwice() async {
        let space = space("s1")
        let session = FakeShareSession(signedIn: true, cached: snapshot([space]), fresh: snapshot([space]))
        let model = makeModel(session: session)
        await model.bootstrap()

        var phaseAfterRefresh: ZenCompanion.ShareModel.Phase?
        session.duringAddTab = {
            await model.refreshSpaces()
            phaseAfterRefresh = model.phase
            await model.save()
        }
        await model.save()

        XCTAssertEqual(phaseAfterRefresh, .saving)
        XCTAssertEqual(session.added.count, 1)
        XCTAssertEqual(model.phase, .saved)
    }

    /// A refresh that lands after a failed write must not hide the failure.
    ///
    /// The cached snapshot shows the picker before the bootstrap refresh
    /// returns, so a fast tap can fail the write while that refresh is still
    /// in flight. Re-enabling `.pick` there dropped the error line.
    func testRefreshAfterAFailedSaveKeepsTheError() async {
        let space = space("s1")
        let session = FakeShareSession(
            signedIn: true,
            cached: snapshot([space]),
            fresh: snapshot([space])
        )
        let model = makeModel(session: session)
        await model.bootstrap()

        session.failAddTab = true
        await model.save()
        XCTAssertEqual(model.phase, .failed)

        await model.refreshSpaces()

        XCTAssertEqual(model.phase, .failed)
        XCTAssertNotNil(model.error)
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
            cached: snapshot([space], capability: .enabled),
            fresh: snapshot([space], capability: .enabled),
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

    func testPerShareNormalChoiceSavesNormalAndLeavesTheSavedDefaultAlone() async {
        let space = space("s1", folders: [ZenCompanion.ZenFolder(id: "folder-1", name: "Folder")])
        let session = FakeShareSession(
            signedIn: true,
            cached: snapshot([space], capability: .enabled),
            fresh: snapshot([space], capability: .enabled),
            saveKind: .pinned
        )
        let model = makeModel(session: session)
        await model.bootstrap()

        XCTAssertTrue(model.allowsNormalTabs)
        model.selectDestination(ZenCompanion.PinDestination(spaceId: "s1", folderId: "folder-1"))
        model.selectSaveKind(.normal)

        XCTAssertEqual(model.saveKind, .normal)
        XCTAssertEqual(model.destination, ZenCompanion.PinDestination(spaceId: "s1"))

        await model.save()

        XCTAssertEqual(session.added.count, 1)
        XCTAssertEqual(session.added[0].kind, .normal)
        XCTAssertNil(session.added[0].folderId)

        // The next share starts from the persisted setting again.
        let next = makeModel(session: session)
        await next.bootstrap()
        XCTAssertEqual(next.saveKind, .pinned)
    }

    func testPinnedOverrideForOneShareDoesNotChangeTheSavedDefault() async {
        let space = space("s1")
        let session = FakeShareSession(
            signedIn: true,
            cached: snapshot([space], capability: .enabled),
            fresh: snapshot([space], capability: .enabled),
            saveKind: .normal
        )
        let model = makeModel(session: session)
        await model.bootstrap()
        XCTAssertEqual(model.saveKind, .normal)

        model.selectSaveKind(.pinned)
        XCTAssertEqual(model.saveKind, .pinned)

        await model.save()

        XCTAssertEqual(session.added.first?.kind, .pinned)
        let next = makeModel(session: session)
        await next.bootstrap()
        XCTAssertEqual(next.saveKind, .normal)
    }

    /// Without a proven normal-tabs capability the sheet must not offer or
    /// write normal records, even if the stored default says normal.
    func testCapabilityDisabledForcesPinnedAndHidesTheChoice() async {
        let space = space("s1")
        let session = FakeShareSession(
            signedIn: true,
            cached: snapshot([space], capability: .disabled),
            fresh: snapshot([space], capability: .disabled),
            saveKind: .normal
        )
        let model = makeModel(session: session)
        await model.bootstrap()

        XCTAssertFalse(model.allowsNormalTabs)
        XCTAssertEqual(model.saveKind, .pinned)

        model.selectSaveKind(.normal)
        XCTAssertEqual(model.saveKind, .pinned)

        await model.save()
        XCTAssertEqual(session.added.first?.kind, .pinned)
    }

    /// The common older-browser case: no readable prefs record at all still
    /// writes pinned, even when the stored default says normal.
    func testAbsentCapabilitySavesPinnedEvenWhenTheSavedDefaultIsNormal() async {
        let space = space("s1")
        let session = FakeShareSession(
            signedIn: true,
            cached: snapshot([space], capability: .absent),
            fresh: snapshot([space], capability: .absent),
            saveKind: .normal
        )
        let model = makeModel(session: session)
        await model.bootstrap()

        XCTAssertFalse(model.allowsNormalTabs)
        XCTAssertEqual(model.saveKind, .pinned)

        await model.save()
        XCTAssertEqual(session.added.first?.kind, .pinned)
    }

    /// A capability that arrives with the fresh snapshot applies the saved
    /// default, but never replaces a choice the user already made.
    func testFreshCapabilityAppliesTheSavedDefaultUnlessTheUserChose() async {
        let space = space("s1")
        let session = FakeShareSession(
            signedIn: true,
            cached: snapshot([space], capability: .absent),
            fresh: snapshot([space], capability: .enabled),
            saveKind: .normal
        )
        let model = makeModel(session: session)
        XCTAssertFalse(model.allowsNormalTabs)
        XCTAssertEqual(model.saveKind, .pinned)

        await model.bootstrap()

        XCTAssertTrue(model.allowsNormalTabs)
        XCTAssertEqual(model.saveKind, .normal)

        model.selectSaveKind(.pinned)
        session.fresh = snapshot([space], capability: .enabled)
        await model.refreshSpaces()

        XCTAssertEqual(model.saveKind, .pinned)
    }

    /// A capability downgrade must not throw away a folder the user picked
    /// while normal tabs were still allowed.
    func testCapabilityDowngradeKeepsThePickedFolder() async {
        let space = space("s1", folders: [ZenCompanion.ZenFolder(id: "folder-1", name: "Folder")])
        let session = FakeShareSession(
            signedIn: true,
            cached: snapshot([space], capability: .enabled),
            fresh: snapshot([space], capability: .enabled)
        )
        let model = makeModel(session: session)
        await model.bootstrap()
        model.selectDestination(ZenCompanion.PinDestination(spaceId: "s1", folderId: "folder-1"))
        XCTAssertEqual(model.destination.folderId, "folder-1")

        session.fresh = snapshot([space], capability: .disabled)
        await model.refreshSpaces()

        XCTAssertFalse(model.allowsNormalTabs)
        XCTAssertEqual(model.saveKind, .pinned)
        XCTAssertEqual(model.destination, ZenCompanion.PinDestination(spaceId: "s1", folderId: "folder-1"))
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

    func testSharedTextKeepsALinkInTheMiddle() {
        XCTAssertEqual(
            ZenCompanion.ShareLink.httpURL(from: "look at this https://example.com/a nice" as NSString),
            URL(string: "https://example.com/a")
        )
    }

    func testSharedTextTakesTheFirstOfTwoLinks() {
        XCTAssertEqual(
            ZenCompanion.ShareLink.httpURL(
                from: "https://first.example/a and https://second.example/b" as NSString
            ),
            URL(string: "https://first.example/a")
        )
    }

    func testSharedTextWithoutALinkHasNone() {
        XCTAssertNil(ZenCompanion.ShareLink.httpURL(from: "nothing to see here" as NSString))
        XCTAssertNil(ZenCompanion.ShareLink.httpURL(from: "write to hi@example.com" as NSString))
        XCTAssertNil(ZenCompanion.ShareLink.httpURL(from: "file:///tmp/x" as NSString))
    }

    func testSharedLinkEndingASentenceOrSittingInBrackets() {
        XCTAssertEqual(
            ZenCompanion.ShareLink.httpURL(from: "read https://example.com/a. now" as NSString),
            URL(string: "https://example.com/a")
        )
        XCTAssertEqual(
            ZenCompanion.ShareLink.httpURL(from: "read (https://example.com/b) now" as NSString),
            URL(string: "https://example.com/b")
        )
        XCTAssertEqual(
            ZenCompanion.ShareLink.httpURL(from: "(https://example.com/b)" as NSString),
            URL(string: "https://example.com/b")
        )
        // Only the unmatched bracket goes; the pair inside the path stays.
        XCTAssertEqual(
            ZenCompanion.ShareLink.httpURL(
                from: "(https://en.wikipedia.org/wiki/Function_(mathematics))" as NSString
            ),
            URL(string: "https://en.wikipedia.org/wiki/Function_(mathematics)")
        )
    }

    /// A sentence mark right after the address never belongs to it, but a
    /// query or a trailing slash does.
    func testSharedLinkKeepsQueryAndTrailingSlash() {
        XCTAssertEqual(
            ZenCompanion.ShareLink.httpURL(from: "see https://example.com/a?b=c." as NSString),
            URL(string: "https://example.com/a?b=c")
        )
        XCTAssertEqual(
            ZenCompanion.ShareLink.httpURL(from: "https://example.com/path/" as NSString),
            URL(string: "https://example.com/path/")
        )
    }

    func testSharedLinkInQuotesOrFollowedByPunctuation() {
        XCTAssertEqual(
            ZenCompanion.ShareLink.httpURL(from: "he said \"https://example.com/q\"" as NSString),
            URL(string: "https://example.com/q")
        )
        XCTAssertEqual(
            ZenCompanion.ShareLink.httpURL(from: "look (https://example.com/r)." as NSString),
            URL(string: "https://example.com/r")
        )
    }

    /// A mark the sentence added inside its quotes is the address's own.
    func testSharedLinkInQuotesKeepsItsOwnMark() {
        XCTAssertEqual(
            ZenCompanion.ShareLink.httpURL(
                from: "he said \"https://example.com/search?q=wow!\"" as NSString
            ),
            URL(string: "https://example.com/search?q=wow!")
        )
        XCTAssertEqual(
            ZenCompanion.ShareLink.httpURL(from: "\"https://example.com/search?q=wow!\"" as NSString),
            URL(string: "https://example.com/search?q=wow!")
        )
    }

    /// A Unicode domain has a host, so iOS keeps the link too; Foundation
    /// stores the domain in its punycode form.
    func testSharedLinkWithUnicodeDomainIsAccepted() {
        let url = ZenCompanion.ShareLink.httpURL(from: "https://bücher.example/a" as NSString)
        XCTAssertEqual(url?.path, "/a")
        XCTAssertFalse(url?.host?.isEmpty ?? true)
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
        XCTAssertEqual(
            ZenCompanion.ShareLink.headlineTitle(pageTitle: "https://pin.example/page/", url: url),
            "pin.example"
        )
    }

    func testSaveReplacesUrlShapedTitleWithResolvedPageTitle() async {
        let space = space("s1")
        let session = FakeShareSession(
            signedIn: true,
            cached: snapshot([space]),
            fresh: snapshot([space])
        )
        let model = ZenCompanion.ShareModel(
            session: session,
            pageTitle: "https://pin.example/page/",
            url: URL(string: "https://pin.example/page"),
            sleep: { _ in },
            titleResolver: { _, _ in "Pinned Page" }
        )
        await model.resolveSharedTitle()
        XCTAssertEqual(model.pageTitle, "Pinned Page")
        XCTAssertEqual(model.headlineTitle, "Pinned Page")

        await model.bootstrap()
        await model.save()

        XCTAssertEqual(session.added.first?.title, "Pinned Page")
    }

    func testSaveFallsBackToHostWhenTheOnlyNameIsTheUrl() async {
        let space = space("s1")
        let session = FakeShareSession(
            signedIn: true,
            cached: snapshot([space]),
            fresh: snapshot([space])
        )
        let model = makeModel(
            session: session,
            url: URL(string: "https://pin.example/page"),
            pageTitle: "https://pin.example/page"
        )
        await model.bootstrap()
        await model.save()

        XCTAssertEqual(session.added.first?.title, "pin.example")
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
    /// Runs inside `addTab`, before it returns, to simulate work landing mid-save.
    var duringAddTab: (() async -> Void)?
    /// When true, `addTab` fails instead of saving.
    var failAddTab = false

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
        if failAddTab { throw ZenCompanion.SyncError.conflict }
        added.append(Added(url: url, title: title, spaceId: spaceId, folderId: folderId, kind: kind))
        if let hook = duringAddTab {
            duringAddTab = nil
            await hook()
        }
        return fallBackToPinned
            ? ZenCompanion.AddTabOutcome.fallback(recordId: "tab-\(added.count)")
            : ZenCompanion.AddTabOutcome(recordId: "tab-\(added.count)", kind: kind, fellBackToPinned: false)
    }
    func errorText(_ error: Error) -> String { error.localizedDescription }
    func noSpacesErrorText() -> String { "no spaces" }
    func saveKind() -> ZenCompanion.SaveKind { saveKindValue }
}
