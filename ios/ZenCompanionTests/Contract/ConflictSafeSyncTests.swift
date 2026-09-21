import XCTest

@testable import ZenCompanion

/// Conflict-safe write path (shared/contract/SPEC.md §7.2): conditional
/// requests with a local ON/OFF switch. `MutableSyncServer` is a stateful
/// fake transport a "concurrent desktop writer" can mutate between a read
/// and the write, so the 412 re-read/merge/retry-once behavior is exercised
/// hermetically.
final class ConflictSafeSyncTests: XCTestCase {
    private let defaultKeys = SyncCrypto.KeyBundle(
        encryptionKey: Data(repeating: 0x11, count: 32),
        hmacKey: Data(repeating: 0x22, count: 32)
    )

    override func setUp() {
        super.setUp()
        SpacesSyncService.safeSyncEnabledOverride = nil
        SpacesSyncService.deleteCachedSnapshot()
        AppGroup.defaults.removeObject(forKey: "safeSyncEnabled")
    }

    override func tearDown() {
        SpacesSyncService.safeSyncEnabledOverride = nil
        SpacesSyncService.deleteCachedSnapshot()
        AppGroup.defaults.removeObject(forKey: "safeSyncEnabled")
        super.tearDown()
    }

    // MARK: - addTab

    /// (1) A concurrent desktop append collides with the first POST. The
    /// retry must re-read and send the order-preserving union, so both the
    /// external child and the new tab survive in one conditional POST.
    func testAddTabRootRetriesWithUnionAfterConcurrentAppend() async throws {
        let server = MutableSyncServer()
        try seed(server, id: "space-1", kind: "space", data: [
            "uuid": "space-1",
            "name": "Space",
            "children": ["t1"],
        ])
        SpacesSyncService.cache(ZenSnapshot(
            spaces: [ZenSpace(id: "space-1", name: "Space")],
            fetchedAt: Date(timeIntervalSince1970: 0)
        ))

        let keys = defaultKeys
        var injected = false
        server.beforeWrite = { request in
            guard !injected, request.method == "POST" else { return }
            injected = true
            try? server.mutateCleartext(collection: "spaces", id: "space-1", keys: keys) { obj in
                var data = obj["data"] as? [String: Any] ?? [:]
                data["children"] = (data["children"] as? [String] ?? []) + ["external"]
                obj["data"] = data
            }
        }

        let client = makeClient(transport: server)
        let outcome = try await SpacesSyncService.addTab(
            client: client,
            url: URL(string: "https://example.com")!,
            title: "Example",
            to: "space-1"
        )
        let newId = outcome.recordId

        let posts = postRequests(server)
        XCTAssertEqual(posts.count, 2, "a stale condition must be retried exactly once")
        for post in posts {
            XCTAssertNotNil(post.headers["X-If-Unmodified-Since"], "POSTs must be conditional")
        }
        XCTAssertTrue(server.requests.filter { $0.method == "PUT" }.isEmpty, "safe mode must not use sequential PUTs")

        let first = try postRecords(posts[0])
        XCTAssertEqual(Set(first.compactMap { $0["id"] as? String }), ["space-1", newId])

        let retry = try postRecords(posts[1])
        let retrySpace = try recordData(bso(retry, id: "space-1"))
        XCTAssertEqual(retrySpace["children"] as? [String], ["t1", "external", newId])

        let finalSpace = try XCTUnwrap(server.cleartext(collection: "spaces", id: "space-1", keys: defaultKeys))
        XCTAssertEqual((finalSpace["data"] as? [String: Any])?["children"] as? [String], ["t1", "external", newId])
        XCTAssertEqual(
            SpacesSyncService.cachedSnapshot()?.space(id: "space-1")?.pinned.map(\.id),
            [newId],
            "cache updates only after the write applied"
        )
    }

    /// (2a) A folder target takes the tab record and the folder rewrite in
    /// one POST; the space record is untouched.
    func testAddTabFolderTargetWritesTabAndFolderInOnePost() async throws {
        let server = MutableSyncServer()
        try seed(server, id: "space-1", kind: "space", data: ["uuid": "space-1", "name": "Space", "children": []])
        try seed(server, id: "folder-1", kind: "folder", data: [
            "folderId": "folder-1",
            "workspaceUuid": "space-1",
            "children": [],
        ])
        let client = makeClient(transport: server)

        let outcome = try await SpacesSyncService.addTab(
            client: client,
            url: URL(string: "https://example.com")!,
            title: "Example",
            to: "space-1",
            folderId: "folder-1"
        )
        let newId = outcome.recordId

        let posts = postRequests(server)
        XCTAssertEqual(posts.count, 1)
        let records = try postRecords(posts[0])
        XCTAssertEqual(Set(records.compactMap { $0["id"] as? String }), ["folder-1", newId])

        let tab = try decryptedRecord(bso(records, id: newId))
        XCTAssertEqual(tab["kind"] as? String, "tab")
        XCTAssertEqual((tab["data"] as? [String: Any])?["folderId"] as? String, "folder-1")

        let folder = try recordData(bso(records, id: "folder-1"))
        XCTAssertEqual(folder["children"] as? [String], [newId])
    }

    /// (2b) A folder that belongs to another space never matches: the tab
    /// falls back to the space root instead of an unrelated folder.
    func testAddTabFolderInAnotherSpaceFallsBackToRoot() async throws {
        let server = MutableSyncServer()
        try seed(server, id: "space-1", kind: "space", data: ["uuid": "space-1", "name": "Space", "children": []])
        try seed(server, id: "folder-1", kind: "folder", data: [
            "folderId": "folder-1",
            "workspaceUuid": "space-2",
            "children": [],
        ])
        let client = makeClient(transport: server)

        let outcome = try await SpacesSyncService.addTab(
            client: client,
            url: URL(string: "https://example.com")!,
            title: "Example",
            to: "space-1",
            folderId: "folder-1"
        )
        let newId = outcome.recordId

        let records = try postRecords(try XCTUnwrap(postRequests(server).first))
        XCTAssertEqual(Set(records.compactMap { $0["id"] as? String }), ["space-1", newId])
        let tabData = try XCTUnwrap(try decryptedRecord(bso(records, id: newId))["data"] as? [String: Any])
        XCTAssertTrue(tabData["folderId"] is NSNull, "root fallback must clear folderId")
        let spaceData = try recordData(bso(records, id: "space-1"))
        XCTAssertEqual(spaceData["children"] as? [String], [newId])
    }

    /// (2c) When the live collection lacks the space, the cached snapshot is
    /// the parent source, exactly like the legacy path.
    func testAddTabFallsBackToCachedSpaceWhenCollectionLacksIt() async throws {
        let server = MutableSyncServer()
        SpacesSyncService.cache(ZenSnapshot(
            spaces: [ZenSpace(
                id: "space-1",
                name: "Cached",
                pinned: [.tab(ZenTab(id: "t1", url: "https://t1.example", title: "T1"))]
            )],
            fetchedAt: Date(timeIntervalSince1970: 0)
        ))
        let client = makeClient(transport: server)

        let outcome = try await SpacesSyncService.addTab(
            client: client,
            url: URL(string: "https://example.com")!,
            title: "Example",
            to: "space-1"
        )
        let newId = outcome.recordId

        let records = try postRecords(try XCTUnwrap(postRequests(server).first))
        XCTAssertEqual(Set(records.compactMap { $0["id"] as? String }), ["space-1", newId])
        let spaceData = try recordData(bso(records, id: "space-1"))
        XCTAssertEqual(spaceData["uuid"] as? String, "space-1")
        XCTAssertEqual(spaceData["name"] as? String, "Cached")
        XCTAssertEqual(spaceData["children"] as? [String], ["t1", newId])
    }

    // MARK: - addTab, normal kind (SPEC §7 write gating)

    /// Normal write with the enabled pref: `pinned:false`, no folder (even a
    /// requested one is ignored), and the record lands in the normal bucket of
    /// the local cache.
    func testAddTabNormalWritesPinnedFalseIgnoresFolderAndCachesNormalBucket() async throws {
        let server = MutableSyncServer()
        try seed(server, id: "space-1", kind: "space", data: ["uuid": "space-1", "name": "Space", "children": []])
        try seed(server, id: "folder-1", kind: "folder", data: [
            "folderId": "folder-1",
            "workspaceUuid": "space-1",
            "children": [],
        ])
        try seedPrefs(server, values: [SpacesSyncService.normalTabsPrefKey: true])
        SpacesSyncService.cache(ZenSnapshot(
            spaces: [ZenSpace(id: "space-1", name: "Space")],
            fetchedAt: Date(timeIntervalSince1970: 0)
        ))
        let client = makeClient(transport: server)

        let outcome = try await SpacesSyncService.addTab(
            client: client,
            url: URL(string: "https://example.com")!,
            title: "Example",
            to: "space-1",
            folderId: "folder-1",
            kind: .normal
        )

        XCTAssertEqual(outcome.kind, .normal)
        XCTAssertFalse(outcome.fellBackToPinned)

        let records = try postRecords(try XCTUnwrap(postRequests(server).first))
        XCTAssertEqual(Set(records.compactMap { $0["id"] as? String }), ["space-1", outcome.recordId])
        let tabData = try XCTUnwrap(try decryptedRecord(bso(records, id: outcome.recordId))["data"] as? [String: Any])
        XCTAssertEqual(tabData["pinned"] as? Bool, false)
        XCTAssertTrue(tabData["folderId"] is NSNull, "a normal tab never targets a folder")
        let spaceData = try recordData(bso(records, id: "space-1"))
        XCTAssertEqual(spaceData["children"] as? [String], [outcome.recordId])

        let cached = try XCTUnwrap(SpacesSyncService.cachedSnapshot()?.space(id: "space-1"))
        XCTAssertEqual(cached.tabs.map(\.id), [outcome.recordId])
        XCTAssertTrue(cached.pinned.isEmpty)
    }

    /// A normal save lands at the front of the open-tabs region: after pinned
    /// children, before existing unpinned ones — both on the wire and in the
    /// local cache (so the row appears at the top of the open-tabs list).
    func testAddTabNormalInsertsAtFrontOfOpenTabs() async throws {
        let server = MutableSyncServer()
        try seed(server, id: "space-1", kind: "space", data: [
            "uuid": "space-1",
            "name": "Space",
            "children": ["pin-1", "n1"],
        ])
        try seedPrefs(server, values: [SpacesSyncService.normalTabsPrefKey: true])
        SpacesSyncService.cache(ZenSnapshot(
            spaces: [ZenSpace(
                id: "space-1",
                name: "Space",
                pinned: [.tab(ZenTab(id: "pin-1", url: "https://pin.example", title: "Pin"))],
                tabs: [.tab(ZenTab(id: "n1", url: "https://n1.example", title: "N1"))]
            )],
            fetchedAt: Date(timeIntervalSince1970: 0)
        ))
        let client = makeClient(transport: server)

        let outcome = try await SpacesSyncService.addTab(
            client: client,
            url: URL(string: "https://example.com")!,
            title: "Example",
            to: "space-1",
            kind: .normal
        )
        let newId = outcome.recordId

        let spaceData = try recordData(bso(try postRecords(try XCTUnwrap(postRequests(server).first)), id: "space-1"))
        XCTAssertEqual(spaceData["children"] as? [String], ["pin-1", newId, "n1"])

        let cached = try XCTUnwrap(SpacesSyncService.cachedSnapshot()?.space(id: "space-1"))
        XCTAssertEqual(cached.tabs.map(\.id), [newId, "n1"])
        XCTAssertEqual(cached.pinned.map(\.id), ["pin-1"])
    }

    /// Concurrent desktop append + normal save: retry still inserts the new
    /// tab at the front of the unpinned region, and keeps the remote child.
    func testAddTabNormalRetriesInsertingAtFrontOfOpenTabs() async throws {
        let server = MutableSyncServer()
        try seed(server, id: "space-1", kind: "space", data: [
            "uuid": "space-1",
            "name": "Space",
            "children": ["pin-1", "n1"],
        ])
        try seedPrefs(server, values: [SpacesSyncService.normalTabsPrefKey: true])
        SpacesSyncService.cache(ZenSnapshot(
            spaces: [ZenSpace(
                id: "space-1",
                name: "Space",
                pinned: [.tab(ZenTab(id: "pin-1", url: "https://pin.example", title: "Pin"))],
                tabs: [.tab(ZenTab(id: "n1", url: "https://n1.example", title: "N1"))]
            )],
            fetchedAt: Date(timeIntervalSince1970: 0)
        ))

        let keys = defaultKeys
        var injected = false
        server.beforeWrite = { request in
            guard !injected, request.method == "POST" else { return }
            injected = true
            try? server.mutateCleartext(collection: "spaces", id: "space-1", keys: keys) { obj in
                var data = obj["data"] as? [String: Any] ?? [:]
                data["children"] = (data["children"] as? [String] ?? []) + ["external"]
                obj["data"] = data
            }
        }

        let client = makeClient(transport: server)
        let outcome = try await SpacesSyncService.addTab(
            client: client,
            url: URL(string: "https://example.com")!,
            title: "Example",
            to: "space-1",
            kind: .normal
        )
        let newId = outcome.recordId

        XCTAssertEqual(postRequests(server).count, 2)
        let retry = try postRecords(postRequests(server)[1])
        let retrySpace = try recordData(bso(retry, id: "space-1"))
        XCTAssertEqual(retrySpace["children"] as? [String], ["pin-1", newId, "n1", "external"])
    }

    /// Normal write while the pref is off: the write falls back to pinned
    /// (never lose the tab) and reports the fallback.
    func testAddTabNormalFallsBackWhenPrefDisabled() async throws {
        let server = MutableSyncServer()
        try seed(server, id: "space-1", kind: "space", data: ["uuid": "space-1", "name": "Space", "children": []])
        try seedPrefs(server, values: [SpacesSyncService.normalTabsPrefKey: false])
        let client = makeClient(transport: server)

        let outcome = try await SpacesSyncService.addTab(
            client: client,
            url: URL(string: "https://example.com")!,
            title: "Example",
            to: "space-1",
            kind: .normal
        )

        XCTAssertEqual(outcome.kind, .pinned)
        XCTAssertTrue(outcome.fellBackToPinned)

        let records = try postRecords(try XCTUnwrap(postRequests(server).first))
        let tabData = try XCTUnwrap(try decryptedRecord(bso(records, id: outcome.recordId))["data"] as? [String: Any])
        XCTAssertEqual(tabData["pinned"] as? Bool, true)
    }

    /// Normal write while the prefs record is absent: support is unproven, so
    /// the write falls back to pinned and reports the fallback.
    func testAddTabNormalFallsBackWhenPrefsAbsent() async throws {
        let server = MutableSyncServer()
        try seed(server, id: "space-1", kind: "space", data: ["uuid": "space-1", "name": "Space", "children": []])
        let client = makeClient(transport: server)

        let outcome = try await SpacesSyncService.addTab(
            client: client,
            url: URL(string: "https://example.com")!,
            title: "Example",
            to: "space-1",
            kind: .normal
        )

        XCTAssertEqual(outcome.kind, .pinned)
        XCTAssertTrue(outcome.fellBackToPinned)

        let records = try postRecords(try XCTUnwrap(postRequests(server).first))
        let tabData = try XCTUnwrap(try decryptedRecord(bso(records, id: outcome.recordId))["data"] as? [String: Any])
        XCTAssertEqual(tabData["pinned"] as? Bool, true)
    }

    /// A pinned write never reads the prefs collection: the safety re-read is
    /// only for normal writes.
    func testAddTabPinnedDoesNotReadPrefs() async throws {
        let server = MutableSyncServer()
        try seed(server, id: "space-1", kind: "space", data: ["uuid": "space-1", "name": "Space", "children": []])
        let client = makeClient(transport: server)

        _ = try await SpacesSyncService.addTab(
            client: client,
            url: URL(string: "https://example.com")!,
            title: "Example",
            to: "space-1"
        )

        XCTAssertFalse(
            server.requests.contains { $0.url.path.contains("/storage/prefs") },
            "pinned writes must not pay for a prefs read"
        )
    }

    /// (3) A second 412 surfaces as `SyncError.conflict` and leaves the
    /// cache untouched.
    func testSecondPreconditionFailureThrowsConflictAndLeavesCache() async throws {
        let server = MutableSyncServer()
        try seed(server, id: "space-1", kind: "space", data: ["uuid": "space-1", "name": "Space", "children": []])
        let seeded = ZenSnapshot(
            spaces: [ZenSpace(id: "space-1", name: "Space")],
            fetchedAt: Date(timeIntervalSince1970: 0)
        )
        SpacesSyncService.cache(seeded)
        server.beforeWrite = { request in
            if request.method == "POST" {
                server.bumpCollection("spaces")
            }
        }
        let client = makeClient(transport: server)

        do {
            _ = try await SpacesSyncService.addTab(
                client: client,
                url: URL(string: "https://example.com")!,
                title: "Example",
                to: "space-1"
            )
            XCTFail("expected SyncError.conflict")
        } catch let error as SyncError {
            guard case .conflict = error else {
                return XCTFail("expected SyncError.conflict, got \(error)")
            }
        }

        XCTAssertEqual(postRequests(server).count, 2, "the retry must not loop")
        XCTAssertEqual(SpacesSyncService.cachedSnapshot()?.spaces, seeded.spaces)
    }

    /// (3b) A 200 POST that rejects a requested record (`failed`/missing from
    /// `success`) surfaces as `SyncError.conflict` without a retry and leaves
    /// the cache untouched.
    func testPartialPostFailureThrowsConflictAndLeavesCache() async throws {
        let server = MutableSyncServer()
        try seed(server, id: "space-1", kind: "space", data: ["uuid": "space-1", "name": "Space", "children": []])
        let seeded = ZenSnapshot(
            spaces: [ZenSpace(id: "space-1", name: "Space")],
            fetchedAt: Date(timeIntervalSince1970: 0)
        )
        SpacesSyncService.cache(seeded)
        server.forcedFailed = ["space-1": "conflict"]
        let client = makeClient(transport: server)

        do {
            _ = try await SpacesSyncService.addTab(
                client: client,
                url: URL(string: "https://example.com")!,
                title: "Example",
                to: "space-1"
            )
            XCTFail("expected SyncError.conflict")
        } catch let error as SyncError {
            guard case .conflict = error else {
                return XCTFail("expected SyncError.conflict, got \(error)")
            }
        }

        XCTAssertEqual(postRequests(server).count, 1, "a partial failure is not retried")
        XCTAssertEqual(SpacesSyncService.cachedSnapshot()?.spaces, seeded.spaces)
    }

    // MARK: - deleteTab

    /// (4) Deleting a tab in a space is one conditional POST carrying both
    /// the tombstone and the rewritten parent.
    func testDeleteInSpaceSendsTombstoneAndParentInOnePost() async throws {
        let server = MutableSyncServer()
        try seed(server, id: "space-1", kind: "space", data: ["uuid": "space-1", "name": "Space", "children": ["t1", "t2"]])
        try seed(server, id: "t1", kind: "tab", data: ["tabId": "t1", "url": "https://t1.example"])
        try seed(server, id: "t2", kind: "tab", data: ["tabId": "t2", "url": "https://t2.example"])
        let client = makeClient(transport: server)

        try await SpacesSyncService.deleteTab(client: client, id: "t1")

        let posts = postRequests(server)
        XCTAssertEqual(posts.count, 1, "delete must be a single atomic POST")
        XCTAssertTrue(server.requests.filter { $0.method == "PUT" }.isEmpty)
        let records = try postRecords(posts[0])
        XCTAssertEqual(Set(records.compactMap { $0["id"] as? String }), ["space-1", "t1"])

        let tombstone = try decryptedRecord(bso(records, id: "t1"))
        XCTAssertEqual(tombstone["deleted"] as? Bool, true)
        let space = try recordData(bso(records, id: "space-1"))
        XCTAssertEqual(space["children"] as? [String], ["t2"])
    }

    /// (5) Deleting a member of a 2-member split collapses the split: its
    /// tombstone and the parent splice ride the SAME request and the other
    /// member tab record survives.
    func testDeletingSplitMemberCollapsesSplitInSameRequest() async throws {
        let server = MutableSyncServer()
        try seed(server, id: "space-1", kind: "space", data: ["uuid": "space-1", "name": "Space", "children": ["split-1"]])
        try seed(server, id: "split-1", kind: "split", data: ["splitId": "split-1", "tabs": ["t1", "t2"]])
        try seed(server, id: "t1", kind: "tab", data: ["tabId": "t1", "url": "https://t1.example"])
        try seed(server, id: "t2", kind: "tab", data: ["tabId": "t2", "url": "https://t2.example"])
        let client = makeClient(transport: server)

        try await SpacesSyncService.deleteTab(client: client, id: "t1")

        let posts = postRequests(server)
        XCTAssertEqual(posts.count, 1)
        let records = try postRecords(posts[0])
        XCTAssertEqual(Set(records.compactMap { $0["id"] as? String }), ["t1", "split-1", "space-1"])

        XCTAssertEqual(try decryptedRecord(bso(records, id: "t1"))["deleted"] as? Bool, true)
        XCTAssertEqual(try decryptedRecord(bso(records, id: "split-1"))["deleted"] as? Bool, true)
        let space = try recordData(bso(records, id: "space-1"))
        XCTAssertEqual(space["children"] as? [String], ["t2"], "the surviving member is spliced into the parent")

        let survivor = try XCTUnwrap(server.cleartext(collection: "spaces", id: "t2", keys: defaultKeys))
        XCTAssertEqual(survivor["kind"] as? String, "tab")
    }

    /// (6) Deleting a tab only rewrites the essentials buckets that list it.
    func testDeleteFiltersOnlyMatchingEssentialsBuckets() async throws {
        let server = MutableSyncServer()
        try seed(server, id: "layout-1", kind: "layout", data: [
            "spaces": ["space-1"],
            "essentials": ["a": ["t1"], "b": ["t3"]],
        ])
        try seed(server, id: "t1", kind: "tab", data: ["tabId": "t1", "url": "https://t1.example"])
        let client = makeClient(transport: server)

        try await SpacesSyncService.deleteTab(client: client, id: "t1")

        let records = try postRecords(try XCTUnwrap(postRequests(server).first))
        XCTAssertEqual(Set(records.compactMap { $0["id"] as? String }), ["t1", "layout-1"])
        let layout = try recordData(bso(records, id: "layout-1"))
        let essentials = try XCTUnwrap(layout["essentials"] as? [String: Any])
        XCTAssertEqual(essentials["a"] as? [String], [])
        XCTAssertEqual(essentials["b"] as? [String], ["t3"])
    }

    /// (7) Unsplit sends the split tombstone + parent splice in one request;
    /// after a concurrent append the retry recomputes the splice and keeps
    /// the external child.
    func testUnsplitSplicesMembersAndPreservesConcurrentChildOnRetry() async throws {
        let server = MutableSyncServer()
        try seed(server, id: "space-1", kind: "space", data: ["uuid": "space-1", "name": "Space", "children": ["split-1"]])
        try seed(server, id: "split-1", kind: "split", data: ["splitId": "split-1", "tabs": ["t1", "t2"]])
        try seed(server, id: "t1", kind: "tab", data: ["tabId": "t1", "url": "https://t1.example"])
        try seed(server, id: "t2", kind: "tab", data: ["tabId": "t2", "url": "https://t2.example"])

        let keys = defaultKeys
        var injected = false
        server.beforeWrite = { request in
            guard !injected, request.method == "POST" else { return }
            injected = true
            try? server.mutateCleartext(collection: "spaces", id: "space-1", keys: keys) { obj in
                var data = obj["data"] as? [String: Any] ?? [:]
                data["children"] = (data["children"] as? [String] ?? []) + ["external"]
                obj["data"] = data
            }
        }
        let client = makeClient(transport: server)

        try await SpacesSyncService.deleteTab(client: client, id: "split-1")

        let posts = postRequests(server)
        XCTAssertEqual(posts.count, 2)
        let first = try postRecords(posts[0])
        XCTAssertEqual(Set(first.compactMap { $0["id"] as? String }), ["split-1", "space-1"])
        XCTAssertEqual(try decryptedRecord(bso(first, id: "split-1"))["deleted"] as? Bool, true)

        let retry = try postRecords(posts[1])
        let retrySpace = try recordData(bso(retry, id: "space-1"))
        XCTAssertEqual(retrySpace["children"] as? [String], ["t1", "t2", "external"])

        let finalSpace = try XCTUnwrap(server.cleartext(collection: "spaces", id: "space-1", keys: defaultKeys))
        XCTAssertEqual((finalSpace["data"] as? [String: Any])?["children"] as? [String], ["t1", "t2", "external"])
    }

    // MARK: - Conditional read (pagination)

    /// A 412 on page two restarts the WHOLE read once, and the restarted
    /// pages are conditioned on the fresh `X-Last-Modified`.
    func testConditionalReadRestartsOnceOnMidReadConflict() async throws {
        let transport = FakeSyncTransport()
        transport.responder = { index, _ in
            switch index {
            case 0:
                return .init(
                    status: 200,
                    headers: ["x-last-modified": "m1", "x-weave-next-offset": "n1"],
                    body: self.listBody([["id": "a"]])
                )
            case 1:
                return .init(status: 412, headers: ["x-last-modified": "m2"], body: Data())
            case 2:
                return .init(
                    status: 200,
                    headers: ["x-last-modified": "m2", "x-weave-next-offset": "n1"],
                    body: self.listBody([["id": "a"]])
                )
            default:
                return .init(status: 200, headers: ["x-last-modified": "m2"], body: self.listBody([["id": "b"]]))
            }
        }
        let client = makeClient(transport: transport)

        let (records, lastModified) = try await client.getCollectionWithMetadata(collection: "spaces")

        XCTAssertEqual(records.compactMap { $0["id"] as? String }, ["a", "b"])
        XCTAssertEqual(lastModified, "m2")
        XCTAssertEqual(transport.requests.count, 4, "one restart = two full reads")
        XCTAssertNil(transport.requests[0].headers["X-If-Unmodified-Since"])
        XCTAssertEqual(transport.requests[1].headers["X-If-Unmodified-Since"], "m1")
        XCTAssertNil(transport.requests[2].headers["X-If-Unmodified-Since"])
        XCTAssertEqual(transport.requests[3].headers["X-If-Unmodified-Since"], "m2")
    }

    /// A second mid-read 412 surfaces as `SyncError.conflict`; the read is
    /// never retried a third time.
    func testConditionalReadThrowsConflictAfterSecondMidReadConflict() async throws {
        let transport = FakeSyncTransport()
        transport.responder = { index, _ in
            switch index {
            case 0, 2:
                return .init(
                    status: 200,
                    headers: ["x-last-modified": "m1", "x-weave-next-offset": "n1"],
                    body: self.listBody([["id": "a"]])
                )
            default:
                return .init(status: 412, headers: ["x-last-modified": "m2"], body: Data())
            }
        }
        let client = makeClient(transport: transport)

        do {
            _ = try await client.getCollectionWithMetadata(collection: "spaces")
            XCTFail("expected SyncError.conflict")
        } catch let error as SyncError {
            guard case .conflict = error else {
                return XCTFail("expected SyncError.conflict, got \(error)")
            }
        }
        XCTAssertEqual(transport.requests.count, 4, "the read must not loop")
    }

    // MARK: - Keys bootstrap race

    /// (8) A concurrent device that created `crypto/keys` first wins: the
    /// conditional PUT gets 412 and the winner's keys are decrypted and used.
    func testKeysBootstrapRaceUsesWinnerKeysWithoutOverwriting() async throws {
        let kB = Data(repeating: 0xAB, count: 32)
        let syncKeys = SyncCrypto.syncKeyBundle(fromKB: kB)
        let winner = SyncCrypto.KeyBundle(
            encryptionKey: Data(repeating: 0x07, count: 32),
            hmacKey: Data(repeating: 0x09, count: 32)
        )
        let winnerPayload = try SyncCrypto.encryptBSO(
            plaintext: try JSONSerialization.data(withJSONObject: [
                "default": [
                    winner.encryptionKey.base64EncodedString(),
                    winner.hmacKey.base64EncodedString(),
                ],
                "collections": [:] as [String: Any],
            ]),
            keys: syncKeys
        )

        let server = MutableSyncServer()
        var injected = false
        server.beforeWrite = { request in
            guard !injected,
                  request.method == "PUT",
                  request.url.path.hasSuffix("/storage/crypto/keys")
            else { return }
            injected = true
            server.setRecord(collection: "crypto", id: "keys", payload: winnerPayload)
        }

        let client = try await SyncClient(creds: makeCreds(), kB: kB, transport: server)

        let keys = await client.keys(for: "spaces")
        XCTAssertEqual(keys.encryptionKey, winner.encryptionKey)
        XCTAssertEqual(keys.hmacKey, winner.hmacKey)
        XCTAssertEqual(
            server.payload(collection: "crypto", id: "keys"),
            winnerPayload,
            "the losing conditional PUT must not overwrite the winner"
        )
        XCTAssertEqual(server.requests.map(\.method), ["GET", "PUT", "GET"])
        let put = try XCTUnwrap(server.requests.first { $0.method == "PUT" })
        XCTAssertEqual(put.headers["X-If-Unmodified-Since"], "0")
    }

    // MARK: - Legacy switch OFF

    /// (9) With the switch OFF both mutations keep today's exact sequential
    /// unconditional request shape (method/path/body order).
    func testSwitchOffPinsLegacyRequestSequence() async throws {
        SpacesSyncService.safeSyncEnabledOverride = false
        let server = MutableSyncServer()
        try seed(server, id: "space-1", kind: "space", data: ["uuid": "space-1", "name": "Space", "children": ["t1"]])
        try seed(server, id: "t1", kind: "tab", data: ["tabId": "t1", "url": "https://t1.example"])
        let client = makeClient(transport: server)

        let outcome = try await SpacesSyncService.addTab(
            client: client,
            url: URL(string: "https://example.com")!,
            title: "Example",
            to: "space-1"
        )
        let newId = outcome.recordId

        XCTAssertEqual(server.requests.map(\.method), ["GET", "PUT", "PUT"])
        XCTAssertTrue(
            server.requests[0].url.absoluteString.hasSuffix("/storage/spaces?full=1&limit=2500"),
            server.requests[0].url.absoluteString
        )
        XCTAssertTrue(server.requests[1].url.absoluteString.hasSuffix("/storage/spaces/\(newId)"))
        XCTAssertTrue(server.requests[2].url.absoluteString.hasSuffix("/storage/spaces/space-1"))
        for request in server.requests {
            XCTAssertNil(request.headers["X-If-Unmodified-Since"], request.method)
        }
        let tabPut = try XCTUnwrap(JSONSerialization.jsonObject(
            with: XCTUnwrap(server.requests[1].body)
        ) as? [String: Any])
        XCTAssertEqual(tabPut.count, 1, "legacy PUT body is a single {payload} object")
        XCTAssertNotNil(tabPut["payload"])
        let spacePut = try XCTUnwrap(JSONSerialization.jsonObject(
            with: XCTUnwrap(server.requests[2].body)
        ) as? [String: Any])
        let spacePayload = try XCTUnwrap(spacePut["payload"] as? String)
        let spaceCleartext = try JSONSerialization.jsonObject(
            with: SyncCrypto.decryptBSO(payloadJSON: spacePayload, keys: defaultKeys)
        ) as? [String: Any]
        let spaceData = try XCTUnwrap(spaceCleartext?["data"] as? [String: Any])
        XCTAssertEqual(spaceData["children"] as? [String], ["t1", newId])

        server.clearRequests()
        try await SpacesSyncService.deleteTab(client: client, id: "t1")

        XCTAssertEqual(server.requests.map(\.method), ["GET", "PUT", "PUT"])
        XCTAssertTrue(server.requests[0].url.absoluteString.hasSuffix("/storage/spaces?full=1&limit=2500"))
        XCTAssertTrue(server.requests[1].url.absoluteString.hasSuffix("/storage/spaces/t1"))
        XCTAssertTrue(server.requests[2].url.absoluteString.hasSuffix("/storage/spaces/space-1"))
        for request in server.requests {
            XCTAssertNil(request.headers["X-If-Unmodified-Since"], request.method)
        }
        for request in server.requests where request.method == "PUT" {
            let body = try XCTUnwrap(JSONSerialization.jsonObject(
                with: XCTUnwrap(request.body)
            ) as? [String: Any])
            XCTAssertEqual(body.count, 1, "legacy PUT body is a single {payload} object")
        }
    }

    // MARK: - Demo mode

    /// (10) Demo mode never touches the transport, with the safe-sync switch
    /// both ON and OFF.
    func testDemoModePerformsNoTransportCalls() async throws {
        let previousSecure = AccountStore.secureStore
        let previousFiles = AccountStore.fileStore
        let previousTransport = AccountStore.transport
        AccountStore.secureStore = MemorySecureStore()
        AccountStore.fileStore = MemoryFileStore()
        AccountStore.transport = MutableSyncServer()
        defer {
            AccountStore.clear()
            AccountStore.secureStore = previousSecure
            AccountStore.fileStore = previousFiles
            AccountStore.transport = previousTransport
        }
        try AccountStore.save(AccountSnapshot(
            email: "demo@example.com",
            uid: "demo",
            sessionTokenHex: "00",
            kBHex: "00",
            isDemo: true
        ))

        let transport = try XCTUnwrap(AccountStore.transport as? MutableSyncServer)
        let spaceId = try XCTUnwrap(DemoCatalog.snapshot.spaces.first?.id)

        SpacesSyncService.safeSyncEnabledOverride = true
        let onOutcome = try await SpacesSyncService.addTab(
            url: URL(string: "https://example.com")!,
            title: "Demo ON",
            to: spaceId
        )
        let onId = onOutcome.recordId
        _ = try await SpacesSyncService.loadSnapshot()
        XCTAssertTrue(transport.requests.isEmpty, "demo mode must not touch the transport (safe-sync ON)")

        SpacesSyncService.safeSyncEnabledOverride = false
        let offOutcome = try await SpacesSyncService.addTab(
            url: URL(string: "https://example.com")!,
            title: "Demo OFF",
            to: spaceId
        )
        let offId = offOutcome.recordId
        try await SpacesSyncService.deleteTab(id: offId)

        XCTAssertTrue(transport.requests.isEmpty, "demo mode must not touch the transport")
        let cached = try XCTUnwrap(SpacesSyncService.cachedSnapshot()?.space(id: spaceId))
        XCTAssertTrue(cached.pinned.map(\.id).contains(onId), "safe-sync ON demo add must update the cache")
        XCTAssertFalse(cached.pinned.map(\.id).contains(offId), "safe-sync OFF demo delete must update the cache")
    }

    /// Demo mode carries normal tabs, so its capability is `enabled` and a
    /// normal save lands in the normal bucket without any transport call.
    func testDemoModeNormalSaveUsesNormalBucket() async throws {
        let previousSecure = AccountStore.secureStore
        let previousFiles = AccountStore.fileStore
        let previousTransport = AccountStore.transport
        AccountStore.secureStore = MemorySecureStore()
        AccountStore.fileStore = MemoryFileStore()
        AccountStore.transport = MutableSyncServer()
        defer {
            AccountStore.clear()
            AccountStore.secureStore = previousSecure
            AccountStore.fileStore = previousFiles
            AccountStore.transport = previousTransport
        }
        try AccountStore.save(AccountSnapshot(
            email: "demo@example.com",
            uid: "demo",
            sessionTokenHex: "00",
            kBHex: "00",
            isDemo: true
        ))

        let spaceId = try XCTUnwrap(DemoCatalog.snapshot.spaces.first?.id)
        let outcome = try await SpacesSyncService.addTab(
            url: URL(string: "https://example.com")!,
            title: "Demo normal",
            to: spaceId,
            kind: .normal
        )

        XCTAssertEqual(outcome.kind, .normal)
        XCTAssertFalse(outcome.fellBackToPinned)
        let cached = try XCTUnwrap(SpacesSyncService.cachedSnapshot()?.space(id: spaceId))
        XCTAssertEqual(
            cached.tabs.first?.id,
            outcome.recordId,
            "a demo normal save must land at the top of the open-tabs list"
        )
        XCTAssertFalse(cached.pinned.map(\.id).contains(outcome.recordId))
    }

    // MARK: - Helpers

    private func makeCreds() -> TokenServerCreds {
        TokenServerCreds(
            uid: "uid-1",
            apiEndpoint: "https://sync.example.com/1.0/sync/1.5",
            hawkID: "hawk-id",
            hawkKey: Data("hawk-key".utf8),
            expiresAt: Date(timeIntervalSince1970: 4_000_000_000)
        )
    }

    private func makeClient(transport: SyncHTTPTransport) -> SyncClient {
        SyncClient(
            creds: makeCreds(),
            defaultKeys: defaultKeys,
            collectionKeys: [:],
            transport: transport
        )
    }

    private func seed(
        _ server: MutableSyncServer,
        id: String,
        kind: String,
        data: [String: Any]
    ) throws {
        try server.seedCleartext(
            collection: "spaces",
            id: id,
            kind: kind,
            data: data,
            keys: defaultKeys
        )
    }

    /// Seeds the single `prefs` record the normal-tabs write gate reads.
    private func seedPrefs(_ server: MutableSyncServer, values: [String: Any]) throws {
        let cleartext: [String: Any] = ["id": "prefs", "value": values]
        let payload = try SyncCrypto.encryptBSO(
            plaintext: try JSONSerialization.data(withJSONObject: cleartext),
            keys: defaultKeys
        )
        server.setRecord(collection: "prefs", id: "prefs", payload: payload)
    }

    private func postRequests(_ server: MutableSyncServer) -> [SyncHTTPRequest] {
        server.requests.filter { $0.method == "POST" }
    }

    private func postRecords(_ request: SyncHTTPRequest) throws -> [[String: Any]] {
        let body = try XCTUnwrap(request.body)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [[String: Any]])
    }

    private func listBody(_ records: [[String: Any]]) -> Data {
        (try? JSONSerialization.data(withJSONObject: records)) ?? Data()
    }

    private func bso(_ records: [[String: Any]], id: String) throws -> [String: Any] {
        try XCTUnwrap(records.first { $0["id"] as? String == id }, "missing BSO \(id)")
    }

    private func decryptedRecord(_ bso: [String: Any]) throws -> [String: Any] {
        let payload = try XCTUnwrap(bso["payload"] as? String)
        let data = try SyncCrypto.decryptBSO(payloadJSON: payload, keys: defaultKeys)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func recordData(_ bso: [String: Any]) throws -> [String: Any] {
        try XCTUnwrap(try decryptedRecord(bso)["data"] as? [String: Any])
    }
}

/// Stateful Sync storage fake: mutable records with per-BSO and
/// per-collection timestamps, conditional GET/PUT/POST semantics (SPEC §7.2),
/// and a `beforeWrite` hook that lets a test act as a concurrent desktop
/// writer between the client's read and its write.
final class MutableSyncServer: SyncHTTPTransport {
    private(set) var requests: [SyncHTTPRequest] = []
    private(set) var payloads: [String: String] = [:]
    private(set) var itemModified: [String: String] = [:]
    private(set) var collectionModified: [String: String] = [:]
    var beforeWrite: ((SyncHTTPRequest) -> Void)?
    /// Per-record POST failures: id -> server reason. Forced ids are reported
    /// in `failed` and omitted from `success`; the record is not stored.
    var forcedFailed: [String: String] = [:]

    private var clock = 1_000

    func send(_ request: SyncHTTPRequest) async throws -> SyncHTTPResponse {
        requests.append(request)
        if request.method != "GET" { beforeWrite?(request) }

        let components = request.url.path.split(separator: "/").map(String.init)
        guard let storageIndex = components.lastIndex(of: "storage") else {
            return SyncHTTPResponse(statusCode: 500, headers: [:], body: Data())
        }
        let tail = Array(components[(storageIndex + 1)...])
        switch (request.method, tail.count) {
        case ("GET", 1):
            return collectionGet(collection: tail[0], request: request)
        case ("GET", 2):
            return itemGet(collection: tail[0], id: tail[1])
        case ("PUT", 2):
            return itemPut(collection: tail[0], id: tail[1], request: request)
        case ("POST", 1):
            return collectionPost(collection: tail[0], request: request)
        default:
            return SyncHTTPResponse(statusCode: 500, headers: [:], body: Data())
        }
    }

    func clearRequests() {
        requests.removeAll()
    }

    func setRecord(collection: String, id: String, payload: String) {
        payloads[key(collection, id)] = payload
        itemModified[key(collection, id)] = bump()
        collectionModified[collection] = bump()
    }

    func payload(collection: String, id: String) -> String? {
        payloads[key(collection, id)]
    }

    func bumpCollection(_ collection: String) {
        collectionModified[collection] = bump()
    }

    // MARK: Test-side cleartext helpers

    func seedCleartext(
        collection: String,
        id: String,
        kind: String,
        data: [String: Any],
        keys: SyncCrypto.KeyBundle
    ) throws {
        let cleartext: [String: Any] = ["id": id, "kind": kind, "data": data]
        let payload = try SyncCrypto.encryptBSO(
            plaintext: try JSONSerialization.data(withJSONObject: cleartext),
            keys: keys
        )
        setRecord(collection: collection, id: id, payload: payload)
    }

    func cleartext(collection: String, id: String, keys: SyncCrypto.KeyBundle) throws -> [String: Any]? {
        guard let payload = payload(collection: collection, id: id) else { return nil }
        let data = try SyncCrypto.decryptBSO(payloadJSON: payload, keys: keys)
        return try JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    func mutateCleartext(
        collection: String,
        id: String,
        keys: SyncCrypto.KeyBundle,
        _ change: (inout [String: Any]) -> Void
    ) throws {
        guard var object = try cleartext(collection: collection, id: id, keys: keys) else { return }
        change(&object)
        let payload = try SyncCrypto.encryptBSO(
            plaintext: try JSONSerialization.data(withJSONObject: object),
            keys: keys
        )
        setRecord(collection: collection, id: id, payload: payload)
    }

    // MARK: Exchange handling

    private func collectionGet(collection: String, request: SyncHTTPRequest) -> SyncHTTPResponse {
        let current = collectionModified[collection] ?? "0"
        if let condition = request.headers["X-If-Unmodified-Since"], condition != current {
            return SyncHTTPResponse(statusCode: 412, headers: ["x-last-modified": current], body: Data())
        }
        var list: [[String: Any]] = []
        for storedKey in payloads.keys.sorted() {
            let (collectionName, id) = split(storedKey)
            guard collectionName == collection, let payload = payloads[storedKey] else { continue }
            list.append(["id": id, "payload": payload])
        }
        let body = (try? JSONSerialization.data(withJSONObject: list)) ?? Data()
        return SyncHTTPResponse(statusCode: 200, headers: ["x-last-modified": current], body: body)
    }

    private func itemGet(collection: String, id: String) -> SyncHTTPResponse {
        guard let payload = payloads[key(collection, id)] else {
            return SyncHTTPResponse(statusCode: 404, headers: [:], body: Data())
        }
        let body = (try? JSONSerialization.data(withJSONObject: ["id": id, "payload": payload])) ?? Data()
        return SyncHTTPResponse(
            statusCode: 200,
            headers: ["x-last-modified": itemModified[key(collection, id)] ?? "0"],
            body: body
        )
    }

    private func itemPut(collection: String, id: String, request: SyncHTTPRequest) -> SyncHTTPResponse {
        let current = itemModified[key(collection, id)] ?? "0"
        if let condition = request.headers["X-If-Unmodified-Since"], condition != current {
            return SyncHTTPResponse(statusCode: 412, headers: ["x-last-modified": current], body: Data())
        }
        let json = request.body.flatMap {
            try? JSONSerialization.jsonObject(with: $0) as? [String: Any]
        } ?? [:]
        guard let payload = json["payload"] as? String else {
            return SyncHTTPResponse(statusCode: 400, headers: [:], body: Data())
        }
        setRecord(collection: collection, id: id, payload: payload)
        return SyncHTTPResponse(
            statusCode: 200,
            headers: ["x-last-modified": itemModified[key(collection, id)] ?? "0"],
            body: Data()
        )
    }

    private func collectionPost(collection: String, request: SyncHTTPRequest) -> SyncHTTPResponse {
        let current = collectionModified[collection] ?? "0"
        if let condition = request.headers["X-If-Unmodified-Since"], condition != current {
            return SyncHTTPResponse(statusCode: 412, headers: ["x-last-modified": current], body: Data())
        }
        let records = request.body.flatMap {
            try? JSONSerialization.jsonObject(with: $0) as? [[String: Any]]
        } ?? []
        var success: [String] = []
        var failed: [String: String] = [:]
        for record in records {
            guard let id = record["id"] as? String, let payload = record["payload"] as? String else { continue }
            if let reason = forcedFailed[id] {
                failed[id] = reason
                continue
            }
            payloads[key(collection, id)] = payload
            itemModified[key(collection, id)] = bump()
            success.append(id)
        }
        collectionModified[collection] = bump()
        let body = (try? JSONSerialization.data(withJSONObject: [
            "modified": Double(clock),
            "success": success,
            "failed": failed,
        ])) ?? Data()
        return SyncHTTPResponse(statusCode: 200, headers: [:], body: body)
    }

    // MARK: Utilities

    private func key(_ collection: String, _ id: String) -> String {
        "\(collection)/\(id)"
    }

    private func split(_ key: String) -> (String, String) {
        guard let slash = key.firstIndex(of: "/") else { return (key, "") }
        return (String(key[..<slash]), String(key[key.index(after: slash)...]))
    }

    private func bump() -> String {
        clock += 1
        return String(format: "%.2f", Double(clock))
    }
}

private final class MemorySecureStore: AccountSecureStore {
    var stored: Data?

    func read() -> Data? { stored }
    func write(_ data: Data) -> Bool {
        stored = data
        return true
    }
    func delete() { stored = nil }
}

private struct MemoryFileStore: AccountFileStore {
    func read(_ url: URL) -> Data? { nil }
    func write(_ data: Data, to url: URL) throws {}
    func remove(_ url: URL) {}
    func exists(_ url: URL) -> Bool { false }
}
