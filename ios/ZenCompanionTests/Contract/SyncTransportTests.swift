import XCTest

@testable import ZenCompanion

/// Hermetic read-side and request-shape tests for the injectable Sync
/// transport. Uses `FakeSyncTransport` (no network) and the recorded HTTP
/// exchanges in `shared/contract/http/` where a status/header vector is
/// available. Envelopes are built with the real crypto and fixed keys.
final class SyncTransportTests: XCTestCase {
    private let defaultKeys = SyncCrypto.KeyBundle(
        encryptionKey: Data(repeating: 0x11, count: 32),
        hmacKey: Data(repeating: 0x22, count: 32)
    )

    private let spacesKeys = SyncCrypto.KeyBundle(
        encryptionKey: Data(repeating: 0x33, count: 32),
        hmacKey: Data(repeating: 0x44, count: 32)
    )

    private func makeCreds() -> TokenServerCreds {
        TokenServerCreds(
            uid: "uid-1",
            apiEndpoint: "https://sync.example.com/1.0/sync/1.5",
            hawkID: "hawk-id",
            hawkKey: Data("hawk-key".utf8),
            expiresAt: Date(timeIntervalSince1970: 4_000_000_000)
        )
    }

    private func makeClient(
        defaultKeys: SyncCrypto.KeyBundle? = nil,
        collectionKeys: [String: SyncCrypto.KeyBundle] = [:],
        transport: SyncHTTPTransport
    ) -> SyncClient {
        SyncClient(
            creds: makeCreds(),
            defaultKeys: defaultKeys ?? self.defaultKeys,
            collectionKeys: collectionKeys,
            transport: transport
        )
    }

    // MARK: - Fixture loader

    /// The loader's static name list must track `shared/contract/http/` and
    /// every recording must declare contract 1 with a matching id
    /// (SPEC §7.3). The loader fatalErrors loudly on drift; the explicit
    /// asserts below double-check.
    func testHTTPFixtureLoaderTracksDirectory() {
        XCTAssertEqual(HTTPFixtures.all.count, 11, "recording list must track shared/contract/http")
        for name in HTTPFixtures.all {
            let obj = HTTPFixtures.json(name)
            XCTAssertEqual(obj["contract"] as? Int, 1, name)
            XCTAssertEqual(obj["id"] as? String, name, name)
            XCTAssertNotNil(obj["purpose"], "\(name) must document its purpose")
            XCTAssertNotNil(obj["input"], "\(name) must carry the recorded request")
            let response = HTTPFixtures.response(name)
            XCTAssertNotNil(response["status"] as? Int, "\(name) must record a response status")
        }
    }

    // MARK: - Pagination

    /// Two pages follow `X-Weave-Next-Offset`; the second request must carry
    /// the recorded offset (SPEC §7.3, recordings page1/page2).
    func testGetRecordsFollowsNextOffsetAcrossTwoPages() async throws {
        let page1 = HTTPFixtures.response("http-get-pagination-page1")
        let page2 = HTTPFixtures.response("http-get-pagination-page2")
        let transport = FakeSyncTransport()
        transport.stubs = [
            stub(page1, body: listBody([["id": "a"]])),
            stub(page2, body: listBody([["id": "b"]])),
        ]
        let client = makeClient(transport: transport)

        let records = try await client.getRecords(collection: "spaces")

        XCTAssertEqual(records.compactMap { $0["id"] as? String }, ["a", "b"])
        XCTAssertEqual(transport.requests.count, 2)
        XCTAssertTrue(
            transport.requests[0].url.absoluteString.hasSuffix(requestPath("http-get-pagination-page1")),
            transport.requests[0].url.absoluteString
        )
        XCTAssertTrue(
            transport.requests[1].url.absoluteString.hasSuffix(requestPath("http-get-pagination-page2")),
            transport.requests[1].url.absoluteString
        )
        XCTAssertTrue(
            transport.requests[1].url.absoluteString.contains("offset=1700000200"),
            "second page must carry the encoded next offset"
        )
    }

    /// An unchanged next-offset token must stop pagination instead of looping
    /// (recording `http-get-stuck-offset`).
    func testGetRecordsStopsOnStuckOffset() async throws {
        let stuck = HTTPFixtures.response("http-get-stuck-offset")
        let transport = FakeSyncTransport()
        transport.stubs = [
            stub(stuck, body: listBody([["id": "a"]])),
            stub(stuck, body: listBody([["id": "b"]])),
        ]
        let client = makeClient(transport: transport)

        let records = try await client.getRecords(collection: "spaces")

        XCTAssertEqual(records.count, 2)
        XCTAssertEqual(transport.requests.count, 2, "stuck token must stop after the follow-up page")
        XCTAssertTrue(transport.requests[1].url.absoluteString.contains("offset=1700000200"))
    }

    /// A page with a next-offset token but no content stops pagination.
    func testGetRecordsStopsOnEmptyPage() async throws {
        let page1 = HTTPFixtures.response("http-get-pagination-page1")
        let transport = FakeSyncTransport()
        transport.stubs = [stub(page1, body: listBody([]))]
        let client = makeClient(transport: transport)

        let records = try await client.getRecords(collection: "spaces")

        XCTAssertTrue(records.isEmpty)
        XCTAssertEqual(transport.requests.count, 1, "empty page must not be followed")
    }

    /// Hard cap: never fetch more than 50 pages even if the server always
    /// signals another page.
    func testGetRecordsStopsAtFiftyPageCap() async throws {
        let transport = FakeSyncTransport()
        transport.responder = { index, _ in
            .init(
                status: 200,
                headers: ["X-Weave-Next-Offset": "1700000\(index)"],
                body: self.listBody([["id": "r\(index)"]])
            )
        }
        let client = makeClient(transport: transport)

        let records = try await client.getRecords(collection: "spaces")

        XCTAssertEqual(transport.requests.count, 50, "pagination must stop at the 50-page cap")
        XCTAssertEqual(records.count, 50)
    }

    // MARK: - Recent records

    func testGetRecentRecordsUsesNewestPageQuery() async throws {
        let transport = FakeSyncTransport()
        transport.stubs = [.init(status: 200, body: listBody([["id": "r"]]))]
        let client = makeClient(transport: transport)

        let records = try await client.getRecentRecords(collection: "spaces", limit: 5)

        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(transport.requests.count, 1)
        XCTAssertEqual(transport.requests[0].method, "GET")
        XCTAssertTrue(
            transport.requests[0].url.absoluteString.hasSuffix("/storage/spaces?full=1&limit=5&sort=newest"),
            transport.requests[0].url.absoluteString
        )
    }

    // MARK: - Error mapping

    /// 404 on a collection read yields an empty list, not an error
    /// (recording `http-get-404`).
    func testMissingCollectionReturnsEmpty() async throws {
        let transport = FakeSyncTransport()
        transport.stubs = [stub(HTTPFixtures.response("http-get-404"))]
        let client = makeClient(transport: transport)

        let records = try await client.getRecords(collection: "spaces")

        XCTAssertTrue(records.isEmpty)
        XCTAssertEqual(transport.requests.count, 1)
    }

    /// A 500 carrying Hawk clock-skew material must surface both headers in
    /// the error detail (recording `http-get-500-hawk`).
    func testServerErrorCarriesHawkHeadersInDetail() async throws {
        let response = HTTPFixtures.response("http-get-500-hawk")
        let headers = try XCTUnwrap(response["headers"] as? [String: String])
        let transport = FakeSyncTransport()
        transport.stubs = [stub(response)]
        let client = makeClient(transport: transport)

        do {
            _ = try await client.getRecords(collection: "spaces")
            XCTFail("expected a network error")
        } catch let error as SyncError {
            guard case .network(let detail) = error else {
                return XCTFail("expected SyncError.network, got \(error)")
            }
            XCTAssertTrue(detail.contains("HTTP 500 GET /storage/spaces"), detail)
            for name in ["WWW-Authenticate", "X-Timestamp"] {
                let value = try XCTUnwrap(headers[name])
                XCTAssertTrue(detail.contains("\(name): \(value)"), detail)
            }
        }
    }

    /// Transport failures propagate without retry.
    func testTransportErrorPropagatesWithoutRetry() async throws {
        let transport = FakeSyncTransport()
        transport.stubs = [.init(error: URLError(.timedOut))]
        let client = makeClient(transport: transport)

        do {
            _ = try await client.getRecords(collection: "spaces")
            XCTFail("expected a transport error")
        } catch {
            XCTAssertTrue(error is URLError, "expected URLError, got \(error)")
        }
        XCTAssertEqual(transport.requests.count, 1, "transport errors must not be retried here")
    }

    // MARK: - Endpoint scheme enforcement

    /// A non-HTTPS api endpoint fails closed before any request reaches the
    /// transport (SPEC §7).
    func testSyncClientRejectsInsecureEndpoint() async throws {
        let transport = FakeSyncTransport()
        let client = SyncClient(
            creds: TokenServerCreds(
                uid: "uid-1",
                apiEndpoint: "http://example.test/1.5/abc",
                hawkID: "hawk-id",
                hawkKey: Data("hawk-key".utf8),
                expiresAt: Date(timeIntervalSince1970: 4_000_000_000)
            ),
            defaultKeys: defaultKeys,
            collectionKeys: [:],
            transport: transport
        )

        do {
            _ = try await client.infoCollections()
            XCTFail("expected the insecure-endpoint error")
        } catch let error as SyncError {
            guard case .network(let detail) = error else {
                return XCTFail("expected SyncError.network, got \(error)")
            }
            XCTAssertEqual(detail, "insecure sync endpoint")
        }
        XCTAssertTrue(transport.requests.isEmpty, "no request may reach the transport")
    }

    // MARK: - Collection keys bootstrap

    /// Missing payload → PUT fresh keys (default bundle only) → re-read.
    /// The generated keys are random, so only their structure and lengths
    /// are asserted; the re-read keys are fixed and must be installed.
    func testKeysBootstrapWritesDefaultBundleThenReReads() async throws {
        let missing = HTTPFixtures.response("http-keys-missing")
        let kB = Data(repeating: 0xAB, count: 32)
        let syncKeys = SyncCrypto.syncKeyBundle(fromKB: kB)
        let reRead = SyncCrypto.KeyBundle(
            encryptionKey: Data(repeating: 0x07, count: 32),
            hmacKey: Data(repeating: 0x09, count: 32)
        )
        let reReadPayload = try envelope(
            [
                "default": [
                    reRead.encryptionKey.base64EncodedString(),
                    reRead.hmacKey.base64EncodedString(),
                ],
                "collections": [:] as [String: Any],
            ],
            keys: syncKeys
        )

        let transport = FakeSyncTransport()
        transport.stubs = [
            stub(missing, body: jsonBody([:])),
            .init(status: 200, body: Data()),
            .init(status: 200, body: jsonBody(["payload": reReadPayload])),
        ]

        let client = try await SyncClient(creds: makeCreds(), kB: kB, transport: transport)

        XCTAssertEqual(transport.requests.count, 3)
        XCTAssertEqual(transport.requests[0].method, "GET")
        XCTAssertEqual(transport.requests[1].method, "PUT")
        XCTAssertEqual(transport.requests[2].method, "GET")
        for request in transport.requests {
            XCTAssertTrue(request.url.absoluteString.hasSuffix("/storage/crypto/keys"), request.url.absoluteString)
        }

        let putBody = try bodyJSON(transport.requests[1].body)
        let payload = try XCTUnwrap(putBody["payload"] as? String)
        let plaintext = try decrypted(payload, keys: syncKeys)
        let defaults = try XCTUnwrap(plaintext["default"] as? [Any])
        XCTAssertEqual(defaults.count, 2)
        let enc = try XCTUnwrap(defaults[0] as? String)
        let hmac = try XCTUnwrap(defaults[1] as? String)
        XCTAssertEqual(try XCTUnwrap(Data(base64Encoded: enc)).count, 32)
        XCTAssertEqual(try XCTUnwrap(Data(base64Encoded: hmac)).count, 32)
        XCTAssertEqual((plaintext["collections"] as? [String: Any])?.isEmpty, true)

        let installed = await client.keys(for: "spaces")
        XCTAssertEqual(installed.encryptionKey, reRead.encryptionKey)
        XCTAssertEqual(installed.hmacKey, reRead.hmacKey)
    }

    /// A keys payload whose `collections` value is not a bundle map leaves
    /// the override map empty and the default bundle in effect.
    func testKeysPayloadWithMalformedCollectionsYieldsEmptyOverrides() async throws {
        let kB = Data(repeating: 0xCD, count: 32)
        let syncKeys = SyncCrypto.syncKeyBundle(fromKB: kB)
        let payload = try envelope(
            [
                "default": [
                    defaultKeys.encryptionKey.base64EncodedString(),
                    defaultKeys.hmacKey.base64EncodedString(),
                ],
                "collections": "not-a-map",
            ],
            keys: syncKeys
        )
        let transport = FakeSyncTransport()
        transport.stubs = [.init(status: 200, body: jsonBody(["payload": payload]))]

        let client = try await SyncClient(creds: makeCreds(), kB: kB, transport: transport)

        let keys = await client.keys(for: "spaces")
        XCTAssertEqual(keys.encryptionKey, defaultKeys.encryptionKey)
        XCTAssertEqual(keys.hmacKey, defaultKeys.hmacKey)
    }

    /// Per-collection bundles take precedence over the default bundle.
    func testPerCollectionKeysOverrideDefault() async throws {
        let kB = Data(repeating: 0xEF, count: 32)
        let syncKeys = SyncCrypto.syncKeyBundle(fromKB: kB)
        let payload = try envelope(
            [
                "default": [
                    defaultKeys.encryptionKey.base64EncodedString(),
                    defaultKeys.hmacKey.base64EncodedString(),
                ],
                "collections": [
                    "spaces": [
                        spacesKeys.encryptionKey.base64EncodedString(),
                        spacesKeys.hmacKey.base64EncodedString(),
                    ]
                ],
            ],
            keys: syncKeys
        )
        let transport = FakeSyncTransport()
        transport.stubs = [.init(status: 200, body: jsonBody(["payload": payload]))]

        let client = try await SyncClient(creds: makeCreds(), kB: kB, transport: transport)

        let spaces = await client.keys(for: "spaces")
        XCTAssertEqual(spaces.encryptionKey, spacesKeys.encryptionKey)
        XCTAssertEqual(spaces.hmacKey, spacesKeys.hmacKey)
        let tabs = await client.keys(for: "tabs")
        XCTAssertEqual(tabs.encryptionKey, defaultKeys.encryptionKey)
        XCTAssertEqual(tabs.hmacKey, defaultKeys.hmacKey)
    }

    // MARK: - Writes

    /// PUT shape (recording `http-put-200`): percent-encoded braced id,
    /// `{"payload": "<envelope JSON string>"}` body, Hawk auth, and a
    /// cleartext round-trip through the real crypto.
    func testPutRecordRequestShapeAndCleartextRoundTrip() async throws {
        let transport = FakeSyncTransport()
        transport.stubs = [stub(HTTPFixtures.response("http-put-200"))]
        let client = makeClient(transport: transport)

        try await client.putRecord(collection: "spaces", id: "{abc}", object: ["foo": "bar", "n": 1])

        XCTAssertEqual(transport.requests.count, 1)
        let request = transport.requests[0]
        XCTAssertEqual(request.method, "PUT")
        XCTAssertTrue(
            request.url.absoluteString.hasSuffix("/storage/spaces/%7Babc%7D"),
            request.url.absoluteString
        )
        XCTAssertEqual(request.headers["Content-Type"], "application/json")
        XCTAssertEqual(request.headers["User-Agent"], FxAClient.userAgent)
        let authorization = try XCTUnwrap(request.headers["Authorization"])
        XCTAssertTrue(authorization.hasPrefix("Hawk "), authorization)
        XCTAssertTrue(authorization.contains("id=\"hawk-id\""), authorization)
        XCTAssertTrue(authorization.contains("hash=\""), "body-bearing requests must sign the payload")

        let body = try bodyJSON(request.body)
        XCTAssertEqual(body.count, 1, "the PUT body carries exactly the payload envelope")
        let payload = try XCTUnwrap(body["payload"] as? String)
        let cleartext = try decrypted(payload, keys: defaultKeys)
        XCTAssertEqual(cleartext["foo"] as? String, "bar")
        XCTAssertEqual(cleartext["n"] as? Int, 1)
    }

    /// Tombstones are encrypted cleartext `{id, deleted: <boolean true>}`.
    func testPutTombstoneEncryptsDeletedBoolean() async throws {
        let transport = FakeSyncTransport()
        transport.stubs = [stub(HTTPFixtures.response("http-put-200"))]
        let client = makeClient(transport: transport)

        try await client.putTombstone(collection: "spaces", id: "tab-1")

        let body = try bodyJSON(transport.requests[0].body)
        let payload = try XCTUnwrap(body["payload"] as? String)
        let cleartext = try decrypted(payload, keys: defaultKeys)
        XCTAssertEqual(cleartext["id"] as? String, "tab-1")
        let deleted = try XCTUnwrap(cleartext["deleted"] as? Bool)
        XCTAssertTrue(deleted)
        if let number = cleartext["deleted"] as? NSNumber {
            XCTAssertEqual(CFGetTypeID(number), CFBooleanGetTypeID(), "deleted must be a JSON boolean")
        }
    }

    /// Production byte-identity: without an explicit condition, no request
    /// carries `X-If-Unmodified-Since` (SPEC §7.2 legacy unconditional form).
    func testRequestsCarryNoConditionalHeaderByDefault() async throws {
        let transport = FakeSyncTransport()
        transport.stubs = [
            .init(status: 200, body: listBody([["id": "r"]])),
            stub(HTTPFixtures.response("http-put-200")),
        ]
        let client = makeClient(transport: transport)

        _ = try await client.getRecentRecords(collection: "spaces", limit: 5)
        try await client.putRecord(collection: "spaces", id: "id-1", object: ["a": 1])

        XCTAssertEqual(transport.requests.count, 2)
        for request in transport.requests {
            for name in request.headers.keys {
                XCTAssertNotEqual(name.lowercased(), "x-if-unmodified-since", request.method)
            }
        }
    }

    // MARK: - AccountStore seam

    /// `AccountStore.transport` is the injection point production code uses
    /// when it constructs `FxAClient`/`SyncClient` in `connect()`.
    func testAccountStoreTransportSeamFeedsConnect() async throws {
        let previousSecure = AccountStore.secureStore
        let previousFiles = AccountStore.fileStore
        let previousTransport = AccountStore.transport
        let secure = StubSecureStore()
        let transport = FakeSyncTransport()
        transport.stubs = [.init(status: 401, body: jsonBody(["message": "denied"]))]
        AccountStore.secureStore = secure
        AccountStore.fileStore = StubFileStore()
        AccountStore.transport = transport
        defer {
            AccountStore.clear()
            AccountStore.secureStore = previousSecure
            AccountStore.fileStore = previousFiles
            AccountStore.transport = previousTransport
        }

        try AccountStore.save(
            AccountSnapshot(
                email: "a@b.c",
                uid: "u1",
                sessionTokenHex: String(repeating: "01", count: 32),
                kBHex: String(repeating: "ab", count: 32)
            )
        )

        do {
            _ = try await AccountStore.connect()
            XCTFail("expected the stubbed auth failure")
        } catch {
            // Expected: the fake transport answers 401 on every FxA call.
        }

        XCTAssertFalse(transport.requests.isEmpty, "connect() must use the injected transport")
        XCTAssertTrue(
            transport.requests.allSatisfy { $0.url.host == "oauth.accounts.firefox.com" || $0.url.host == "api.accounts.firefox.com" },
            transport.requests.map(\.url.absoluteString).joined(separator: ", ")
        )
    }

    // MARK: - Expired credentials and sign-out races

    /// A storage 401 drops the cached token-server credentials and runs the
    /// operation once more with fresh ones, so an early server-side expiry
    /// no longer fails every refresh until the cached credentials time out.
    func testStorage401RefreshesCredentialsAndRetriesOnce() async throws {
        let server = FxAAndSyncServer(kB: accountKB, storageKeys: defaultKeys)
        server.storage401Remaining = 1
        try installAccount(transport: server)
        defer { restoreAccountStore() }

        _ = try await SpacesSyncService.loadSnapshot()

        XCTAssertEqual(server.tokenServerCalls, 2, "the 401 must force a fresh token-server exchange")
        XCTAssertEqual(server.storage401Remaining, 0)
    }

    /// Rename, move and pin go through the same credential refresh as the
    /// other sync entry points.
    func testRenameMoveAndPinRetryAfterStorage401() async throws {
        let server = FxAAndSyncServer(kB: accountKB, storageKeys: defaultKeys)
        try installAccount(transport: server)
        defer { restoreAccountStore() }

        let ops: [() async throws -> Void] = [
            { try await SpacesSyncService.renameTab(id: "t1", label: "Work") },
            { try await SpacesSyncService.moveTab(id: "t1", toFolder: nil, in: "space-1") },
            { try await SpacesSyncService.pinTab(id: "t1", in: "space-1") },
        ]
        for op in ops {
            AccountStore.invalidateCreds()
            let before = server.tokenServerCalls
            server.storage401Remaining = 1

            // The empty collection has no t1, so the retried write ends in a conflict.
            do {
                try await op()
                XCTFail("expected SyncError.conflict")
            } catch let error as SyncError {
                guard case .conflict = error else { return XCTFail("expected .conflict, got \(error)") }
            }
            XCTAssertEqual(server.tokenServerCalls, before + 2, "the 401 must force a fresh token-server exchange")
            XCTAssertEqual(server.storage401Remaining, 0)
        }
    }

    /// A 401 that persists after the refresh surfaces instead of looping, and
    /// tells the app to sign out: refreshing credentials did not help, so the
    /// stored session is dead.
    func testPersistentStorage401SurfacesAfterOneRetry() async throws {
        let server = FxAAndSyncServer(kB: accountKB, storageKeys: defaultKeys)
        server.storage401Remaining = 10
        try installAccount(transport: server)
        defer { restoreAccountStore() }
        let invalidated = expectation(forNotification: .zenCompanionSessionInvalidated, object: nil)

        do {
            _ = try await SpacesSyncService.loadSnapshot()
            XCTFail("expected SyncError.unauthorized")
        } catch let error as SyncError {
            guard case .unauthorized = error else { return XCTFail("expected .unauthorized, got \(error)") }
            XCTAssertTrue(error.isSessionInvalid)
        }
        await fulfillment(of: [invalidated], timeout: 1)
        XCTAssertEqual(server.tokenServerCalls, 2)
    }

    /// A 401 caused by a device-environment problem is not a revoked session.
    /// Clock skew, a stale credential generation and a node that disabled new
    /// users must never sign the user out: a new sign-in would not fix them.
    func testStorage401DeviceEnvironmentReasonsDoNotInvalidateSession() async throws {
        for reason in ["invalid-timestamp", "invalid-generation", "new-users-disabled", "error"] {
            let server = FxAAndSyncServer(kB: accountKB, storageKeys: defaultKeys)
            server.storage401Remaining = 10
            server.storage401Reason = reason
            try installAccount(transport: server)
            defer { restoreAccountStore() }

            do {
                _ = try await SpacesSyncService.loadSnapshot()
                XCTFail("\(reason): expected SyncError.unauthorized")
            } catch let error as SyncError {
                guard case .unauthorized(let sessionInvalid) = error else {
                    return XCTFail("\(reason): expected .unauthorized, got \(error)")
                }
                XCTAssertFalse(sessionInvalid, reason)
            }
        }
    }

    /// A bare 401 without the storage node's Hawk challenge (proxy, captive
    /// portal) is not a verdict on the account either.
    func testBareStorage401IsNotASessionVerdict() async throws {
        let server = FxAAndSyncServer(kB: accountKB, storageKeys: defaultKeys)
        server.storage401Remaining = 10
        server.storage401IncludesHawk = false
        server.storage401Reason = ""
        try installAccount(transport: server)
        defer { restoreAccountStore() }

        do {
            _ = try await SpacesSyncService.loadSnapshot()
            XCTFail("expected SyncError.unauthorized")
        } catch let error as SyncError {
            guard case .unauthorized(let sessionInvalid) = error else {
                return XCTFail("expected .unauthorized, got \(error)")
            }
            XCTAssertFalse(sessionInvalid)
        }
    }

    /// Signing out while credentials are being fetched must not leave them
    /// cached for the next account, and the fetch reports signed-out.
    func testSignOutDuringCredentialFetchDropsTheResult() async throws {
        let server = FxAAndSyncServer(kB: accountKB, storageKeys: defaultKeys)
        server.onTokenServer = { AccountStore.clear() }
        try installAccount(transport: server)
        defer { restoreAccountStore() }

        do {
            _ = try await AccountStore.connect()
            XCTFail("expected SyncError.notSignedIn")
        } catch let error as SyncError {
            guard case .notSignedIn = error else { return XCTFail("expected .notSignedIn, got \(error)") }
        }

        server.onTokenServer = nil
        try AccountStore.save(testAccount)
        _ = try await AccountStore.connect()
        XCTAssertEqual(server.tokenServerCalls, 2, "the dropped credentials must not be reused")
    }

    // MARK: - Helpers

    private let accountKB = Data(repeating: 0xAB, count: 32)

    private var testAccount: AccountSnapshot {
        AccountSnapshot(
            email: "a@b.c",
            uid: "u1",
            sessionTokenHex: String(repeating: "01", count: 32),
            kBHex: String(repeating: "ab", count: 32)
        )
    }

    private var savedStores: (AccountSecureStore, AccountFileStore, SyncHTTPTransport)?

    private func installAccount(transport: SyncHTTPTransport) throws {
        savedStores = (AccountStore.secureStore, AccountStore.fileStore, AccountStore.transport)
        AccountStore.secureStore = StubSecureStore()
        AccountStore.fileStore = StubFileStore()
        AccountStore.transport = transport
        SpacesSyncService.deleteCachedSnapshot()
        try AccountStore.save(testAccount)
    }

    private func restoreAccountStore() {
        AccountStore.clear()
        SpacesSyncService.deleteCachedSnapshot()
        if let saved = savedStores {
            AccountStore.secureStore = saved.0
            AccountStore.fileStore = saved.1
            AccountStore.transport = saved.2
        }
    }

    private func requestPath(_ fixture: String) -> String {
        let input = HTTPFixtures.json(fixture)["input"] as! [String: Any]
        let request = input["request"] as! [String: Any]
        return request["path"] as! String
    }

    private func stub(_ response: [String: Any], body: Data = Data()) -> FakeSyncTransport.Stub {
        .init(
            status: response["status"] as! Int,
            headers: response["headers"] as! [String: String],
            body: body
        )
    }

    private func jsonBody(_ object: Any) -> Data {
        (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
    }

    private func listBody(_ records: [[String: Any]]) -> Data {
        (try? JSONSerialization.data(withJSONObject: records)) ?? Data()
    }

    private func bodyJSON(_ data: Data?) throws -> [String: Any] {
        let data = try XCTUnwrap(data)
        return try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func envelope(_ cleartext: [String: Any], keys: SyncCrypto.KeyBundle) throws -> String {
        try SyncCrypto.encryptBSO(plaintext: try JSONSerialization.data(withJSONObject: cleartext), keys: keys)
    }

    private func decrypted(_ payload: String, keys: SyncCrypto.KeyBundle) throws -> [String: Any] {
        let data = try SyncCrypto.decryptBSO(payloadJSON: payload, keys: keys)
        return try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
}

/// Records every request and answers from a FIFO of stubs or a responder
/// closure, so tests never touch the network.
final class FakeSyncTransport: SyncHTTPTransport {
    struct Stub {
        var status: Int = 200
        var headers: [String: String] = [:]
        var body: Data = Data()
        var error: Error?
    }

    private(set) var requests: [SyncHTTPRequest] = []
    var stubs: [Stub] = []
    var responder: ((Int, SyncHTTPRequest) -> Stub)?

    func send(_ request: SyncHTTPRequest) async throws -> SyncHTTPResponse {
        let index = requests.count
        requests.append(request)

        let stub: Stub
        if let responder {
            stub = responder(index, request)
        } else if !stubs.isEmpty {
            stub = stubs.removeFirst()
        } else {
            stub = Stub()
        }
        if let error = stub.error { throw error }

        var headers: [String: String] = [:]
        for (name, value) in stub.headers {
            headers[name.lowercased()] = value
        }
        return SyncHTTPResponse(statusCode: stub.status, headers: headers, body: stub.body)
    }
}

private final class StubSecureStore: AccountSecureStore {
    var stored: Data?

    func read() -> Data? { stored }
    func write(_ data: Data) -> Bool {
        stored = data
        return true
    }
    func delete() { stored = nil }
}

private struct StubFileStore: AccountFileStore {
    func read(_ url: URL) -> Data? { nil }
    func write(_ data: Data, to url: URL) throws {}
    func remove(_ url: URL) {}
    func exists(_ url: URL) -> Bool { false }
}

/// Answers the FxA OAuth, scoped-key and token-server calls plus the Sync
/// storage reads one `AccountStore.connect()` + snapshot load makes. Routed by
/// URL and locked, because the snapshot load reads two collections at once.
final class FxAAndSyncServer: SyncHTTPTransport {
    private let lock = NSLock()
    private let kB: Data
    private let storageKeys: SyncCrypto.KeyBundle
    private var _tokenServerCalls = 0
    private var _storage401Remaining = 0
    /// Reason string the storage node reports on its 401 (`invalid-credentials`
    /// for a real dead session, `invalid-timestamp` for clock skew, …).
    var storage401Reason = "invalid-credentials"
    /// False simulates a bare 401 from a proxy/captive portal without Hawk
    /// challenge or X-Timestamp.
    var storage401IncludesHawk = true
    var onTokenServer: (() -> Void)?

    init(kB: Data, storageKeys: SyncCrypto.KeyBundle) {
        self.kB = kB
        self.storageKeys = storageKeys
    }

    var tokenServerCalls: Int { lock.withLock { _tokenServerCalls } }
    var storage401Remaining: Int {
        get { lock.withLock { _storage401Remaining } }
        set { lock.withLock { _storage401Remaining = newValue } }
    }

    func send(_ request: SyncHTTPRequest) async throws -> SyncHTTPResponse {
        let host = request.url.host ?? ""
        let path = request.url.path
        func json(_ object: Any, status: Int = 200) -> SyncHTTPResponse {
            SyncHTTPResponse(
                statusCode: status,
                headers: ["x-last-modified": "1.00"],
                body: (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
            )
        }
        if host == "oauth.accounts.firefox.com" || path.hasSuffix("/oauth/token") {
            return json(["access_token": "access"])
        }
        if path.hasSuffix("/account/scoped-key-data") {
            return json(["https://identity.mozilla.com/apps/oldsync": ["keyRotationTimestamp": 0]])
        }
        if host == "token.services.mozilla.com" {
            let call = lock.withLock { () -> Int in
                _tokenServerCalls += 1
                return _tokenServerCalls
            }
            onTokenServer?()
            return json([
                "uid": "1",
                "api_endpoint": "https://sync.example.com/1.0/sync/1.5",
                "id": "hawk-\(call)",
                "key": "key-\(call)",
                "duration": 3600,
            ])
        }
        guard host == "sync.example.com" else {
            return SyncHTTPResponse(statusCode: 500, headers: [:], body: Data())
        }
        if path.hasSuffix("/storage/crypto/keys") {
            let keys: [String: Any] = [
                "default": [storageKeys.encryptionKey.base64EncodedString(), storageKeys.hmacKey.base64EncodedString()],
                "collections": [:] as [String: Any],
            ]
            let payload = try SyncCrypto.encryptBSO(
                plaintext: try JSONSerialization.data(withJSONObject: keys),
                keys: SyncCrypto.syncKeyBundle(fromKB: kB)
            )
            return json(["id": "keys", "payload": payload])
        }
        if path.hasSuffix("/storage/meta/global") {
            return SyncHTTPResponse(statusCode: 404, headers: [:], body: Data())
        }
        if path.hasSuffix("/storage/spaces") {
            let reject = lock.withLock { () -> Bool in
                guard _storage401Remaining > 0 else { return false }
                _storage401Remaining -= 1
                return true
            }
            if reject {
                var headers: [String: String] = [:]
                if storage401IncludesHawk {
                    headers["www-authenticate"] = "Hawk ts=\"1700000050.00\", ttl=\"60\", nonce=\"dGVzdG5vbmNl\""
                    headers["x-timestamp"] = "1700000050.00"
                }
                let body = storage401Reason.isEmpty
                    ? Data()
                    : (try? JSONSerialization.data(withJSONObject: ["status": storage401Reason])) ?? Data()
                return SyncHTTPResponse(statusCode: 401, headers: headers, body: body)
            }
        }
        return json([] as [Any])
    }
}
