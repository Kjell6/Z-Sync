import CommonCrypto
import XCTest

@testable import ZenCompanion

/// Contract conformance suite: every fixture under `shared/contract/fixtures/`
/// (see shared/contract/SPEC.md) is a known-answer vector this suite must
/// reproduce. Fixture groups are covered one test per group; field-level
/// detail for the single-case wire fixtures lives in `CryptoTests`.
final class ContractFixtureTests: XCTestCase {
    /// input/expect of a single-case fixture.
    private func vector(_ name: String) -> (input: [String: Any], expect: [String: Any]) {
        let fixture = ContractFixtures.json(name)
        return (fixture["input"] as! [String: Any], fixture["expect"] as! [String: Any])
    }

    // MARK: - Contract integrity

    /// The loader's static name list must track the fixture directory and
    /// every fixture must declare contract 1 (the loader fatalErrors loudly
    /// on drift; the explicit asserts below double-check).
    func testEveryFixtureDeclaresContractVersion1() {
        XCTAssertEqual(ContractFixtures.all.count, 28, "fixture list must track shared/contract/fixtures")
        for name in ContractFixtures.all {
            let obj = ContractFixtures.json(name)
            XCTAssertEqual(obj["contract"] as? Int, 1, name)
            XCTAssertEqual(obj["id"] as? String, name, name)
            XCTAssertNotNil(obj["purpose"], "\(name) must document its purpose")
        }
    }

    // MARK: - Wire decode table

    /// Single-case wire fixtures: expect.dropped / expect.decodesAs pin the
    /// decode verdict (field-level assertions live in CryptoTests).
    /// `wire-folder-missing-folderid` is excluded — its verdict is folder-
    /// shaped-but-never-matching, covered by the D2 tests below. Multi-case
    /// groups (ignored records, prefs) have their own tables.
    func testWireDecodeTable() throws {
        let fixtures = [
            "wire-deleted-string",
            "wire-folder-basic",
            "wire-folder-live-object",
            "wire-layout-basic",
            "wire-space-basic",
            "wire-space-numeric-uuid",
            "wire-space-object-dots",
            "wire-space-rgb-dots",
            "wire-split-basic",
            "wire-split-normal-pinned-false",
            "wire-tab-normal-pinned-false",
            "wire-tab-pinned-default",
            "wire-tab-pinned-string-false",
        ]
        XCTAssertEqual(Set(fixtures).count, fixtures.count, "no duplicate table entries")
        for name in fixtures {
            let (input, expect) = vector(name)
            let decoded = ZenSpacesDecoder.decode(id: input["id"] as? String ?? name, cleartext: input)
            if let dropped = expect["dropped"] as? Bool, dropped {
                XCTAssertNil(decoded, "\(name) must be dropped")
            } else if let kind = expect["decodesAs"] as? String {
                guard let decoded else {
                    return XCTFail("\(name) must decode as \(kind), got nil")
                }
                let got: String
                switch decoded {
                case .space: got = "space"
                case .tab: got = "tab"
                case .folder: got = "folder"
                case .split: got = "split"
                case .layout: got = "layout"
                }
                XCTAssertEqual(got, kind, name)
            } else {
                XCTFail("\(name): expect carries neither dropped nor decodesAs")
            }
        }
    }

    // MARK: - Ignored records (all cases)

    func testIgnoredRecordsAreDropped() {
        for record in ContractFixtures.cases("wire-ignored-records") {
            let caseId = record["id"] as? String ?? "?"
            let input = record["input"] as! [String: Any]
            let expect = record["expect"] as! [String: Any]
            XCTAssertEqual(expect["dropped"] as? Bool, true, caseId)
            XCTAssertEqual(expect["decodesAs"] as? String, nil, caseId)
            XCTAssertNil(
                ZenSpacesDecoder.decode(id: input["id"] as? String ?? caseId, cleartext: input),
                "\(caseId) must be ignored"
            )
        }
    }

    // MARK: - Prefs parser

    func testPrefsNormalTabsShapes() {
        for record in ContractFixtures.cases("wire-prefs-normal-tabs") {
            let input = record["input"] as! [String: Any]
            let expect = record["expect"] as! [String: Any]
            let parsed = SpacesSyncService.prefBool(input["value"])
            if let expected = expect["prefBool"] as? Bool {
                XCTAssertEqual(parsed, expected, "case \(record["id"] as? String ?? "?")")
            } else {
                XCTAssertNil(parsed, "case \(record["id"] as? String ?? "?") must yield nil")
            }
        }
    }

    /// Write-gating capability table (SPEC §7): key presence is the
    /// version-support signal; only a value parsing true enables normal
    /// writes. Covers `wire-prefs-normal-tabs-capability`.
    func testPrefsNormalTabsCapabilityTable() {
        for record in ContractFixtures.cases("wire-prefs-normal-tabs-capability") {
            let caseId = record["id"] as? String ?? "?"
            let input = record["input"] as! [String: Any]
            let expect = record["expect"] as! [String: Any]
            let capability = SpacesSyncService.normalTabsCapability(
                prefsRecordPresent: input["prefsRecordPresent"] as? Bool ?? false,
                values: input["values"] as? [String: Any]
            )
            XCTAssertEqual(
                capability.rawValue,
                expect["normalTabsCapability"] as? String,
                "case \(caseId)"
            )
        }
    }

    // MARK: - D2 target-folder predicate

    /// `wire-folder-missing-folderid`: the record is folder-shaped but never
    /// matches any target-folder request — neither a nil target nor a named
    /// one. iOS's typed decoder also drops it (required `folderId` missing,
    /// SPEC §2 rule 5); matching runs on the raw data and must refuse.
    func testFolderMissingFolderIdNeverMatchesAnyTarget() throws {
        let fixture = ContractFixtures.json("wire-folder-missing-folderid")
        let input = fixture["input"] as! [String: Any]
        let expect = fixture["expect"] as! [String: Any]
        let data = try XCTUnwrap(input["data"] as? [String: Any])

        XCTAssertEqual(input["kind"] as? String, "folder")
        XCTAssertEqual(expect["decodesAsFolder"] as? Bool, true)
        XCTAssertNil(ZenSpacesDecoder.decode(id: input["id"] as! String, cleartext: input))

        for request in try XCTUnwrap(input["targetRequests"] as? [Any]) {
            let target: String? = request is NSNull ? nil : request as? String
            XCTAssertFalse(
                SpacesSyncService.isTargetFolder(folderId: target, data: data),
                "target \(String(describing: target)) must not match a folder without folderId"
            )
        }
        XCTAssertFalse(SpacesSyncService.isTargetFolder(folderId: nil, data: data), "matchesNilTarget")
        XCTAssertFalse(SpacesSyncService.isTargetFolder(folderId: "folder-1", data: data), "matchesFolder1")
    }

    /// Positive and negative predicate table (SPEC §3.5 rules 1–3).
    func testTargetFolderPredicateTable() {
        let folder: [String: Any] = ["folderId": "folder-1", "workspaceUuid": "space-1"]
        XCTAssertTrue(SpacesSyncService.isTargetFolder(folderId: "folder-1", data: folder))
        XCTAssertFalse(SpacesSyncService.isTargetFolder(folderId: nil, data: folder), "nil target never matches a folder")
        XCTAssertFalse(SpacesSyncService.isTargetFolder(folderId: "", data: folder), "empty target never matches")
        XCTAssertFalse(SpacesSyncService.isTargetFolder(folderId: "folder-2", data: folder))
        XCTAssertFalse(SpacesSyncService.isTargetFolder(folderId: "folder-1", data: ["workspaceUuid": "space-1"]), "missing folderId")
        XCTAssertFalse(SpacesSyncService.isTargetFolder(folderId: "folder-1", data: ["folderId": NSNull()]), "null folderId")
        XCTAssertFalse(SpacesSyncService.isTargetFolder(folderId: "folder-1", data: ["folderId": ""]), "empty folderId")
        XCTAssertFalse(SpacesSyncService.isTargetFolder(folderId: "folder-1", data: ["folderId": 42]), "non-string folderId")
    }

    // MARK: - D1 local cache (non-contractual, pinned behavior)

    /// The local snapshot cache is device-local and non-contractual
    /// (SPEC §8): this pins the current Swift enum encoding (`{"_0": …}`)
    /// without migration — Android writes a different shape by design.
    func testLocalCacheKeepsSwiftEnumShape() throws {
        let snapshot = ZenSnapshot(
            spaces: [
                ZenSpace(
                    id: "s1",
                    name: "Work",
                    pinned: [
                        .tab(ZenTab(id: "tab-1", url: "https://example.com", title: "Example")),
                        .split(ZenSplit(id: "split-1", gridType: "vsep", tabs: [
                            ZenTab(id: "tab-2", url: "https://example.org", title: "Org"),
                            ZenTab(id: "tab-3", url: "https://example.net", title: "Net"),
                        ])),
                    ]
                )
            ],
            fetchedAt: .distantPast
        )
        let data = try JSONEncoder().encode(snapshot)
        let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        let space = try XCTUnwrap((object["spaces"] as? [[String: Any]])?.first)
        let pinned = try XCTUnwrap(space["pinned"] as? [[String: Any]])

        let tab = try XCTUnwrap(pinned[0]["tab"] as? [String: Any])
        let tabPayload = try XCTUnwrap(tab["_0"] as? [String: Any])
        XCTAssertEqual(tabPayload["id"] as? String, "tab-1")

        let split = try XCTUnwrap(pinned[1]["split"] as? [String: Any])
        let splitPayload = try XCTUnwrap(split["_0"] as? [String: Any])
        XCTAssertEqual(splitPayload["id"] as? String, "split-1")

        let back = try JSONDecoder().decode(ZenSnapshot.self, from: data)
        XCTAssertEqual(back.spaces[0].pinned.map(\.id), ["tab-1", "split-1"])
        XCTAssertEqual(back, snapshot)
    }

    // MARK: - Auth errno mapping (D3)

    /// Fixture-driven errno mapping (SPEC §7.1): HTTP >= 400 with errno 103 →
    /// totpRequired, any other errno → auth. Platform-specific fallback shapes
    /// (missing message, 2xx/3xx never error) stay in
    /// testNon103AuthErrorsMapToAuthMessage.
    func testAuthErrnoFixtureMapsErrorKinds() {
        for fixtureCase in ContractFixtures.cases("auth-errno-103") {
            let input = fixtureCase["input"] as! [String: Any]
            let expect = fixtureCase["expect"] as! [String: Any]
            let body = input["body"] as! [String: Any]
            let result = FxAClient.authError(status: input["httpStatus"] as! Int, body: body)
            switch expect["errorKind"] as! String {
            case "totpRequired":
                guard case .totpRequired = result else {
                    return XCTFail("case \(fixtureCase["id"] as? String ?? "?"): expected totpRequired, got \(String(describing: result))")
                }
            case "auth":
                guard case .auth = result else {
                    return XCTFail("case \(fixtureCase["id"] as? String ?? "?"): expected .auth, got \(String(describing: result))")
                }
            default:
                XCTFail("case \(fixtureCase["id"] as? String ?? "?"): unknown expect errorKind \(expect["errorKind"] as! String)")
            }
        }
    }

    func testNon103AuthErrorsMapToAuthMessage() {
        guard case .auth(let message) = FxAClient.authError(
            status: 400, body: ["errno": 107, "error": "Bad request", "message": "Invalid parameter"]
        ) else { return XCTFail("expected .auth") }
        XCTAssertEqual(message, "Invalid parameter")

        guard case .auth(let fallback) = FxAClient.authError(status: 503, body: ["error": "Service unavailable"]) else {
            return XCTFail("expected .auth")
        }
        XCTAssertEqual(fallback, "Service unavailable")

        guard case .auth(let statusText) = FxAClient.authError(status: 500, body: [:]) else {
            return XCTFail("expected .auth")
        }
        XCTAssertEqual(statusText, "HTTP 500")

        // 2xx/3xx are never mapped to errors.
        XCTAssertNil(FxAClient.authError(status: 200, body: ["errno": 103]))
        XCTAssertNil(FxAClient.authError(status: 302, body: [:]))
    }

    // MARK: - Crypto vectors

    func testHKDFRFC5869Case1Vector() throws {
        let (input, expect) = vector("crypto-hkdf-rfc5869-case1")
        let okm = FxACrypto.hkdf(
            secret: try FxACrypto.unhex(input["ikmHex"] as! String),
            salt: try FxACrypto.unhex(input["saltHex"] as! String),
            info: try FxACrypto.unhex(input["infoHex"] as! String),
            length: input["length"] as! Int
        )
        XCTAssertEqual(FxACrypto.hex(okm), expect["okmHex"] as! String)
    }

    func testSyncKeyBundleFromZeroKBVector() throws {
        let (input, expect) = vector("crypto-sync-key-bundle-kb-zero")
        let bundle = SyncCrypto.syncKeyBundle(fromKB: try FxACrypto.unhex(input["kbHex"] as! String))
        XCTAssertEqual(FxACrypto.hex(bundle.encryptionKey), expect["encryptionKeyHex"] as! String)
        XCTAssertEqual(FxACrypto.hex(bundle.hmacKey), expect["hmacKeyHex"] as! String)
    }

    /// Regression for the `hkdf(salt:)` parameter threading: production-style
    /// empty-salt derivation must stay byte-identical to the fixture keys
    /// (SPEC §4: empty salt == 32 zero bytes, RFC 5869 §2.2).
    func testSyncKeyBundleEmptySaltMatchesFixture() throws {
        let (input, expect) = vector("crypto-sync-key-bundle-kb-zero")
        let kB = try FxACrypto.unhex(input["kbHex"] as! String)
        let info = Data((input["infoUtf8"] as! String).utf8)
        let defaultSalt = FxACrypto.hkdf(secret: kB, info: info, length: 64)
        let explicitEmptySalt = FxACrypto.hkdf(secret: kB, salt: Data(), info: info, length: 64)
        XCTAssertEqual(defaultSalt, explicitEmptySalt, "default and explicit empty salt must agree")
        XCTAssertEqual(FxACrypto.hex(explicitEmptySalt.prefix(32)), expect["encryptionKeyHex"] as! String)
        XCTAssertEqual(FxACrypto.hex(explicitEmptySalt.suffix(32)), expect["hmacKeyHex"] as! String)
    }

    func testBSOEnvelopeValidVector() throws {
        let (input, expect) = vector("crypto-bso-envelope-valid")
        let keys = SyncCrypto.KeyBundle(
            encryptionKey: try FxACrypto.unhex(input["encryptionKeyHex"] as! String),
            hmacKey: try FxACrypto.unhex(input["hmacKeyHex"] as! String)
        )
        let envelope = try XCTUnwrap(input["envelope"] as? [String: Any])
        let payloadJSON = String(data: try JSONSerialization.data(withJSONObject: envelope), encoding: .utf8)!

        // Plaintext round-trip through the production decrypt path.
        let plaintext = try SyncCrypto.decryptBSO(payloadJSON: payloadJSON, keys: keys)
        XCTAssertEqual(String(data: plaintext, encoding: .utf8), expect["plaintextUtf8"] as! String)
        XCTAssertEqual(FxACrypto.hex(plaintext), expect["plaintextHex"] as! String)

        // The HMAC is computed over the base64 ciphertext STRING bytes.
        let ciphertextB64 = try XCTUnwrap(envelope["ciphertext"] as? String)
        XCTAssertEqual(
            FxACrypto.hex(FxACrypto.hmacSHA256(key: keys.hmacKey, data: Data(ciphertextB64.utf8))),
            try XCTUnwrap(envelope["hmac"] as? String)
        )

        // AES-256-CBC/PKCS7 with the fixture's fixed IV reproduces the
        // exact ciphertext.
        let iv = try XCTUnwrap(Data(base64Encoded: envelope["IV"] as! String))
        let recomputed = try aes256CBCEncrypt(plaintext, key: keys.encryptionKey, iv: iv)
        XCTAssertEqual(recomputed.base64EncodedString(), expect["recomputedCiphertext"] as! String)
    }

    func testBSOEnvelopeTamperedHMACFails() throws {
        let (input, expect) = vector("crypto-bso-envelope-tampered-hmac")
        let keys = SyncCrypto.KeyBundle(
            encryptionKey: try FxACrypto.unhex(input["encryptionKeyHex"] as! String),
            hmacKey: try FxACrypto.unhex(input["hmacKeyHex"] as! String)
        )
        let payloadJSON = String(
            data: try JSONSerialization.data(withJSONObject: try XCTUnwrap(input["envelope"] as? [String: Any])),
            encoding: .utf8
        )!
        XCTAssertEqual(expect["decryptOk"] as? Bool, false)
        XCTAssertThrowsError(try SyncCrypto.decryptBSO(payloadJSON: payloadJSON, keys: keys)) { error in
            XCTAssertEqual((error as? SyncError)?.errorDescription, expect["error"] as? String)
        }
    }

    func testUnbundleAccountKeysVector() throws {
        let (input, expect) = vector("crypto-unbundle-account-keys")
        let plaintext = try FxACrypto.unbundle(
            bundleKey: try FxACrypto.unhex(input["bundleKeyHex"] as! String),
            namespace: input["namespace"] as! String,
            payload: try FxACrypto.unhex(input["payloadHex"] as! String)
        )
        XCTAssertEqual(String(data: plaintext, encoding: .utf8), expect["plaintextUtf8"] as! String)
        XCTAssertEqual(expect["hmacValid"] as? Bool, true)
    }

    func testClientStateBytesVector() throws {
        let (input, expect) = vector("crypto-client-state-bytes-kb-zero")
        let state = FxACrypto.clientStateBytes(kB: try FxACrypto.unhex(input["kbHex"] as! String))
        XCTAssertEqual(FxACrypto.hex(state), expect["stateHex"] as! String)
        XCTAssertEqual(unpaddedBase64URL(state), expect["stateBase64Url"] as! String)
    }

    func testTokenMaterialVector() throws {
        let (input, expect) = vector("crypto-token-material-session-token")
        let material = FxACrypto.tokenMaterial(
            token: try FxACrypto.unhex(input["tokenHex"] as! String),
            type: input["type"] as! String
        )
        XCTAssertEqual(material.id, expect["idHex"] as! String)
        XCTAssertEqual(FxACrypto.hex(material.authKey), expect["authKeyHex"] as! String)
        XCTAssertEqual(FxACrypto.hex(material.bundleKey), expect["bundleKeyHex"] as! String)
    }

    // MARK: - Hawk vectors

    func testHawkAuthorizationResourceVector() throws {
        let (input, expect) = vector("hawk-authorization-resource")
        let key = try FxACrypto.unhex(input["keyHex"] as! String)
        let url = try XCTUnwrap(URL(string: input["url"] as! String))
        XCTAssertNil(input["payloadHash"] as? String, "vector signs without a payload hash")
        let header = HawkAuth.authorization(
            method: input["method"] as! String,
            url: url,
            id: input["id"] as! String,
            key: key,
            payloadHash: nil,
            resource: input["resource"] as! String,
            fixedTimestamp: input["fixedTimestamp"] as? Int,
            fixedNonce: input["fixedNonce"] as! String
        )
        XCTAssertEqual(header, expect["authorization"] as! String)

        // The normalized preimage is exactly the ten fixture lines.
        let normalized = (expect["normalizedLines"] as! [String]).joined(separator: "\n")
        XCTAssertEqual(normalized, expect["normalized"] as! String)
        XCTAssertEqual(HawkAuth.macFor(normalized: normalized, key: key), expect["macBase64"] as! String)
    }

    func testHawkPayloadHashVector() throws {
        let (input, expect) = vector("hawk-payload-hash")
        let body = Data((input["bodyUtf8"] as! String).utf8)
        XCTAssertEqual(
            HawkAuth.payloadHash(body, contentType: input["contentType"] as! String),
            expect["hashBase64"] as! String
        )
        // Normalization truncates at the first ';' and trims — the charset
        // form is not fixture-covered, the fixture pins the plain type.
        XCTAssertEqual(
            HawkAuth.payloadHash(body, contentType: "application/json; charset=utf-8"),
            expect["hashBase64"] as! String
        )
    }

    // MARK: - BSO ids

    func testBSOIdPercentEncodingVector() {
        for record in ContractFixtures.cases("bso-ids-percent-encoding") {
            let input = record["input"] as! [String: Any]
            let expect = record["expect"] as! [String: Any]
            XCTAssertEqual(
                SyncClient.encodedBSOId(input["id"] as! String),
                expect["encoded"] as! String,
                "case \(record["id"] as? String ?? "?")"
            )
        }
    }

    // MARK: - Helpers

    private func unpaddedBase64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Test-local AES-256-CBC/PKCS7 encryptor mirroring SyncCrypto's CCCrypt
    /// settings, so the envelope vector can pin the exact ciphertext without
    /// exposing production internals. SyncCrypto itself is not editable here.
    private func aes256CBCEncrypt(_ data: Data, key: Data, iv: Data) throws -> Data {
        var out = Data(count: data.count + kCCBlockSizeAES128)
        var moved = 0
        let outCount = out.count
        let status = out.withUnsafeMutableBytes { outBytes in
            data.withUnsafeBytes { inBytes in
                key.withUnsafeBytes { keyBytes in
                    iv.withUnsafeBytes { ivBytes in
                        CCCrypt(
                            CCOperation(kCCEncrypt),
                            CCAlgorithm(kCCAlgorithmAES),
                            CCOptions(kCCOptionPKCS7Padding),
                            keyBytes.baseAddress, key.count,
                            ivBytes.baseAddress,
                            inBytes.baseAddress, data.count,
                            outBytes.baseAddress, outCount,
                            &moved
                        )
                    }
                }
            }
        }
        guard status == kCCSuccess else { throw SyncError.crypto("aes \(status)") }
        out.removeSubrange(moved..<out.count)
        return out
    }
}
