import XCTest

@testable import ZenCompanion

/// Container-specific essentials: the synced Zen pref wins, `.automatic`
/// falls back to wire-bucket inference, an explicit app choice overrides both,
/// and the per-space lookup mirrors Zen Desktop's `_shouldShowTab` rules.
final class EssentialsGroupingTests: XCTestCase {
    private func tab(_ id: String) -> ZenTab {
        ZenTab(id: id, url: "https://\(id).example", title: id)
    }

    private func space(_ id: String, containerGuid: String? = nil) -> ZenSpace {
        ZenSpace(id: id, name: id, containerGuid: containerGuid)
    }

    private func snapshot(
        spaces: [ZenSpace],
        essentials: [String: [ZenTab]],
        separatePref: Bool? = nil
    ) -> ZenSnapshot {
        ZenSnapshot(
            spaces: spaces,
            essentials: essentials,
            separateEssentialsPref: separatePref,
            fetchedAt: .distantPast
        )
    }

    private func ids(_ tabs: [ZenTab]) -> [String] { tabs.map(\.id) }

    // MARK: Inference

    func testAllDefaultBucketsInferShared() {
        let snap = snapshot(
            spaces: [space("work", containerGuid: "container-work")],
            essentials: ["default": [tab("a"), tab("b")]]
        )
        XCTAssertFalse(snap.isContainerSpecific(grouping: .automatic))
        XCTAssertEqual(
            ["a", "b"],
            ids(snap.essentials(for: space("work", containerGuid: "container-work"))),
            "all-default essentials must show on container spaces too"
        )
    }

    func testEmptyEssentialsInferShared() {
        let snap = snapshot(spaces: [space("s")], essentials: [:])
        XCTAssertFalse(snap.isContainerSpecific(grouping: .automatic))
        XCTAssertTrue(snap.essentials(for: space("s")).isEmpty)
    }

    func testContainerBucketsInferContainerSpecific() {
        let snap = snapshot(
            spaces: [space("work", containerGuid: "container-work")],
            essentials: ["container-work": [tab("a")]]
        )
        XCTAssertTrue(snap.isContainerSpecific(grouping: .automatic))
        XCTAssertEqual(["a"], ids(snap.essentials(for: space("work", containerGuid: "container-work"))))
    }

    // MARK: Synced pref and explicit override

    func testSyncedPrefWinsOverInference() {
        let separate = snapshot(
            spaces: [space("work", containerGuid: "container-work")],
            essentials: ["container-work": [tab("a")]],
            separatePref: true
        )
        XCTAssertTrue(separate.isContainerSpecific(grouping: .automatic))

        let shared = snapshot(
            spaces: [space("work", containerGuid: "container-work")],
            essentials: ["container-work": [tab("a")]],
            separatePref: false
        )
        XCTAssertFalse(shared.isContainerSpecific(grouping: .automatic))
        XCTAssertEqual(
            ["a"],
            ids(shared.essentials(for: space("work", containerGuid: "container-work"))),
            "pref off shows every essential on every space"
        )
    }

    func testExplicitOverrideWinsOverSyncedPref() {
        let prefOn = snapshot(
            spaces: [space("work", containerGuid: "container-work"), space("plain")],
            essentials: ["container-work": [tab("a")]],
            separatePref: true
        )
        XCTAssertEqual(
            ["a"],
            ids(prefOn.essentials(for: space("plain"), grouping: .shared))
        )

        let prefOff = snapshot(
            spaces: [space("work", containerGuid: "container-work"), space("plain")],
            essentials: ["container-work": [tab("a")]],
            separatePref: false
        )
        XCTAssertTrue(prefOff.essentials(for: space("plain"), grouping: .containerSpecific).isEmpty)
        XCTAssertEqual(
            ["a"],
            ids(prefOff.essentials(for: space("work", containerGuid: "container-work"), grouping: .containerSpecific))
        )
    }

    // MARK: Per-space lookup

    func testSharedUnionKeepsDefaultFirstAndDeduplicates() {
        let snap = snapshot(
            spaces: [space("s")],
            essentials: [
                "b": [tab("t3")],
                "default": [tab("t1"), tab("t2")],
            ]
        )
        XCTAssertEqual(["t1", "t2", "t3"], ids(snap.essentials(for: space("s"))))
    }

    func testContainerSpaceReadsOnlyItsOwnBucket() {
        let snap = snapshot(
            spaces: [space("work", containerGuid: "container-work"), space("plain")],
            essentials: [
                "default": [tab("d")],
                "container-work": [tab("w")],
            ]
        )
        XCTAssertEqual(["w"], ids(snap.essentials(for: space("work", containerGuid: "container-work"))))
    }

    func testDefaultSpaceGetsDefaultPlusOrphanContainerBuckets() {
        let snap = snapshot(
            spaces: [space("plain"), space("work", containerGuid: "container-work")],
            essentials: [
                "default": [tab("d")],
                "container-work": [tab("w")],
                "container-orphan": [tab("o")],
            ]
        )
        XCTAssertEqual(
            ["d", "o"],
            ids(snap.essentials(for: space("plain"))),
            "orphan containers stay reachable from a container-less space"
        )
    }

    func testGridsMatchComparesEffectiveTabLists() {
        let a: [ZenTab] = [tab("a")]
        let b: [ZenTab] = [tab("b")]
        let none: [ZenTab] = []
        XCTAssertTrue(essentialsGridsMatch(a, a))
        XCTAssertFalse(essentialsGridsMatch(a, b))
        XCTAssertFalse(essentialsGridsMatch(a, none))
        XCTAssertTrue(essentialsGridsMatch(none, none))
    }

    // MARK: Cache

    func testSnapshotCacheRoundTripsSeparatePref() throws {
        let snap = snapshot(
            spaces: [space("s")],
            essentials: ["default": [tab("a")]],
            separatePref: false
        )
        let decoded = try JSONDecoder().decode(ZenSnapshot.self, from: JSONEncoder().encode(snap))
        XCTAssertEqual(decoded.separateEssentialsPref, false)
        XCTAssertFalse(decoded.isContainerSpecific(grouping: .automatic))
    }

    func testLegacyCacheWithoutPrefDecodesAndInfers() throws {
        let json = """
        {"spaces":[{"id":"s","name":"S","pinned":[],"tabs":[]}],
         "essentials":{"default":[{"id":"a","url":"https://a.example","title":"a"}]},
         "fetchedAt":7200000.0}
        """
        let decoded = try JSONDecoder().decode(ZenSnapshot.self, from: Data(json.utf8))
        XCTAssertNil(decoded.separateEssentialsPref)
        XCTAssertFalse(decoded.isContainerSpecific(grouping: .automatic))
    }

    // MARK: Synced prefs record

    func testSyncedPrefsReadsSeparateEssentials() async throws {
        let keys = SyncCrypto.KeyBundle(
            encryptionKey: Data(repeating: 0x11, count: 32),
            hmacKey: Data(repeating: 0x22, count: 32)
        )
        let server = MutableSyncServer()
        let cleartext: [String: Any] = [
            "id": "prefs",
            "value": [
                SpacesSyncService.normalTabsPrefKey: false,
                SpacesSyncService.separateEssentialsPrefKey: true,
            ],
        ]
        let payload = try SyncCrypto.encryptBSO(
            plaintext: try JSONSerialization.data(withJSONObject: cleartext),
            keys: keys
        )
        server.setRecord(collection: "prefs", id: "prefs", payload: payload)

        let prefs = await SpacesSyncService.syncedPrefs(client: makeClient(keys: keys, transport: server))

        XCTAssertFalse(prefs.normalTabs)
        XCTAssertEqual(prefs.separateEssentials, true)
    }

    func testSyncedPrefsWithoutSeparateKeyIsNil() async throws {
        let keys = SyncCrypto.KeyBundle(
            encryptionKey: Data(repeating: 0x33, count: 32),
            hmacKey: Data(repeating: 0x44, count: 32)
        )
        let server = MutableSyncServer()
        let cleartext: [String: Any] = [
            "id": "prefs",
            "value": [SpacesSyncService.normalTabsPrefKey: true],
        ]
        let payload = try SyncCrypto.encryptBSO(
            plaintext: try JSONSerialization.data(withJSONObject: cleartext),
            keys: keys
        )
        server.setRecord(collection: "prefs", id: "prefs", payload: payload)

        let prefs = await SpacesSyncService.syncedPrefs(client: makeClient(keys: keys, transport: server))

        XCTAssertTrue(prefs.normalTabs)
        XCTAssertNil(prefs.separateEssentials, "an absent pref must not be invented")
    }

    private func makeClient(keys: SyncCrypto.KeyBundle, transport: SyncHTTPTransport) -> SyncClient {
        SyncClient(
            creds: TokenServerCreds(
                uid: "uid",
                apiEndpoint: "https://sync.example.com/1.0/sync/1.5",
                hawkID: "hawk",
                hawkKey: Data("hawk".utf8),
                expiresAt: Date(timeIntervalSince1970: 4_000_000_000)
            ),
            defaultKeys: keys,
            collectionKeys: [:],
            transport: transport
        )
    }
}

/// "Save shared tabs as" lives in both stores (AppGroup for the Share
/// Extension, standard for app-only reads), defaulting to `.pinned`.
@MainActor
final class SaveKindPreferenceTests: XCTestCase {
    private let key = PreferenceKeys.saveKind

    override func setUp() {
        super.setUp()
        AppGroup.defaults.removeObject(forKey: key)
        UserDefaults.standard.removeObject(forKey: key)
    }

    override func tearDown() {
        AppGroup.defaults.removeObject(forKey: key)
        UserDefaults.standard.removeObject(forKey: key)
        super.tearDown()
    }

    func testSettingWritesBothStores() throws {
        let model = SettingsModel()
        XCTAssertEqual(model.saveKind, .pinned, "the default must stay pinned")

        model.saveKind = .normal

        XCTAssertEqual(
            try XCTUnwrap(AppGroup.defaults.string(forKey: key)),
            SaveKind.normal.rawValue
        )
        XCTAssertEqual(
            try XCTUnwrap(UserDefaults.standard.string(forKey: key)),
            SaveKind.normal.rawValue
        )
    }

    func testAppGroupWinsOverStandardOnLoad() {
        AppGroup.defaults.set(SaveKind.pinned.rawValue, forKey: key)
        UserDefaults.standard.set(SaveKind.normal.rawValue, forKey: key)
        XCTAssertEqual(SettingsModel().saveKind, .pinned)

        AppGroup.defaults.set(SaveKind.normal.rawValue, forKey: key)
        XCTAssertEqual(SettingsModel().saveKind, .normal)
    }

    func testStandardUsedWhenAppGroupMissing() {
        UserDefaults.standard.set(SaveKind.normal.rawValue, forKey: key)
        XCTAssertEqual(SettingsModel().saveKind, .normal)
    }

    func testUnknownStoredValueFallsBackToPinned() {
        AppGroup.defaults.set("garbage", forKey: key)
        UserDefaults.standard.set(SaveKind.normal.rawValue, forKey: key)
        XCTAssertEqual(SettingsModel().saveKind, .normal, "an unreadable AppGroup value falls through to standard")

        UserDefaults.standard.set("garbage", forKey: key)
        XCTAssertEqual(SettingsModel().saveKind, .pinned)
    }
}
