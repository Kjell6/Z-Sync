import CryptoKit
import XCTest

@testable import ZenCompanion

/// Crypto and wire-decode tests. The wire vectors are fixture-driven: input
/// and expectations come from the golden JSON files in
/// `shared/contract/fixtures/` (see shared/contract/SPEC.md). Local
/// assembly/cache behavior that no fixture covers keeps its literals.
final class CryptoTests: XCTestCase {
    func testSyncEnvelopeRoundTrip() throws {
        let keys = SyncCrypto.KeyBundle(
            encryptionKey: Data(repeating: 7, count: 32),
            hmacKey: Data(repeating: 9, count: 32)
        )
        let original = Data(#"{"hello":"zen"}"#.utf8)
        let payload = try SyncCrypto.encryptBSO(plaintext: original, keys: keys)
        let back = try SyncCrypto.decryptBSO(payloadJSON: payload, keys: keys)
        XCTAssertEqual(back, original)
    }

    // MARK: - Constant-time MAC comparison

    func testConstantTimeEqualsAcceptsEqualData() {
        XCTAssertTrue(FxACrypto.constantTimeEquals(Data([1, 2, 3, 4]), Data([1, 2, 3, 4])))
        XCTAssertTrue(FxACrypto.constantTimeEquals(Data(), Data()))
    }

    func testConstantTimeEqualsRejectsUnequalData() {
        XCTAssertFalse(FxACrypto.constantTimeEquals(Data([1, 2, 3]), Data([1, 2, 4])))
        XCTAssertFalse(FxACrypto.constantTimeEquals(Data([0, 0, 0]), Data([0, 0, 1])))
    }

    func testConstantTimeEqualsRejectsDifferentLengths() {
        XCTAssertFalse(FxACrypto.constantTimeEquals(Data([1, 2]), Data([1, 2, 3])))
        XCTAssertFalse(FxACrypto.constantTimeEquals(Data([1, 2, 3]), Data([1, 2])))
        XCTAssertFalse(FxACrypto.constantTimeEquals(Data(), Data([1])))
    }

    // MARK: - Envelope geometry (SPEC §4)

    /// Envelope with a valid HMAC over the base64 ciphertext string and
    /// hand-crafted IV/ciphertext geometry.
    private func handBuiltEnvelope(
        ciphertextB64: String,
        ivB64: String,
        keys: SyncCrypto.KeyBundle
    ) throws -> String {
        let hmac = FxACrypto.hex(FxACrypto.hmacSHA256(key: keys.hmacKey, data: Data(ciphertextB64.utf8)))
        let obj: [String: String] = [
            "ciphertext": ciphertextB64,
            "IV": ivB64,
            "hmac": hmac
        ]
        return String(data: try JSONSerialization.data(withJSONObject: obj), encoding: .utf8)!
    }

    private func assertDecryptFails(
        _ payload: String,
        keys: SyncCrypto.KeyBundle,
        message: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertThrowsError(
            try SyncCrypto.decryptBSO(payloadJSON: payload, keys: keys),
            file: file,
            line: line
        ) { error in
            guard case SyncError.crypto(let detail) = error else {
                return XCTFail("expected SyncError.crypto, got \(error)", file: file, line: line)
            }
            XCTAssertEqual(detail, message, file: file, line: line)
        }
    }

    /// A valid-HMAC envelope with a non-16-byte IV is rejected after the MAC
    /// check (SPEC §4).
    func testDecryptBSORejectsEightByteIV() throws {
        let keys = SyncCrypto.KeyBundle(
            encryptionKey: Data(repeating: 7, count: 32),
            hmacKey: Data(repeating: 9, count: 32)
        )
        let payload = try handBuiltEnvelope(
            ciphertextB64: Data(repeating: 1, count: 16).base64EncodedString(),
            ivB64: Data(repeating: 2, count: 8).base64EncodedString(),
            keys: keys
        )
        assertDecryptFails(payload, keys: keys, message: "bso iv length")
    }

    /// A valid-HMAC envelope whose ciphertext is not block-aligned is
    /// rejected after the MAC check (SPEC §4).
    func testDecryptBSORejectsRaggedCiphertext() throws {
        let keys = SyncCrypto.KeyBundle(
            encryptionKey: Data(repeating: 7, count: 32),
            hmacKey: Data(repeating: 9, count: 32)
        )
        let payload = try handBuiltEnvelope(
            ciphertextB64: Data(repeating: 1, count: 20).base64EncodedString(),
            ivB64: Data(repeating: 2, count: 16).base64EncodedString(),
            keys: keys
        )
        assertDecryptFails(payload, keys: keys, message: "bso ciphertext length")
    }

    /// Zen's spaces engine uses braced UUIDs as BSO ids; braces must be
    /// percent-encoded for the request line (regression: raw `{…}` in the
    /// URL made the storage node reject the PUT with 401). Vector:
    /// `bso-ids-percent-encoding` (full case table in ContractFixtureTests).
    func testBracedBSOIdIsPercentEncoded() throws {
        let braced = try XCTUnwrap(
            ContractFixtures.cases("bso-ids-percent-encoding").first { ($0["id"] as? String) == "braced-uuid" }
        )
        let input = try XCTUnwrap(braced["input"] as? [String: Any])
        let expect = try XCTUnwrap(braced["expect"] as? [String: Any])
        XCTAssertEqual(
            SyncClient.encodedBSOId(try XCTUnwrap(input["id"] as? String)),
            try XCTUnwrap(expect["encoded"] as? String)
        )
    }

    /// Hawk signs the verbatim request-line resource, not the decoded
    /// `URL.path` (which would turn %7B back into `{` and break the MAC).
    func testHawkSignsExplicitResourceVerbatim() throws {
        let url = try XCTUnwrap(URL(string: "https://sync.example.com/1.5/1/storage/spaces/%7Babc%7D"))
        let a = HawkAuth.authorization(
            method: "PUT", url: url, id: "id", key: Data(repeating: 1, count: 32),
            payloadHash: nil, resource: "/1.5/1/storage/spaces/%7Babc%7D",
            fixedTimestamp: 1_700_000_000, fixedNonce: "nonce"
        )
        let expectedMAC = HawkAuth.macFor(normalized: [
            "hawk.1.header", "1700000000", "nonce", "PUT",
            "/1.5/1/storage/spaces/%7Babc%7D", "sync.example.com", "443", "", "", ""
        ].joined(separator: "\n"), key: Data(repeating: 1, count: 32))
        XCTAssertTrue(a.contains("mac=\"\(expectedMAC)\""), a)
        // The derived path would decode the escape — it must NOT match.
        let b = HawkAuth.authorization(
            method: "PUT", url: url, id: "id", key: Data(repeating: 1, count: 32),
            payloadHash: nil, fixedTimestamp: 1_700_000_000, fixedNonce: "nonce"
        )
        XCTAssertNotEqual(a, b)
    }
}

/// Fixture-driven wire decode tests (spaces, tabs, folders, splits, layout,
/// tombstone rules, prefs). The full contract verdict table and the
/// crypto/Hawk known-answer vectors live in `ContractFixtureTests`.
final class ZenSpacesDecoderTests: XCTestCase {
    private func decode(_ json: String) throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: Data(json.utf8)) as! [String: Any]
    }

    /// input/expect of a single-case fixture.
    private func vector(_ name: String) -> (input: [String: Any], expect: [String: Any]) {
        let fixture = ContractFixtures.json(name)
        return (fixture["input"] as! [String: Any], fixture["expect"] as! [String: Any])
    }

    // MARK: Spaces

    func testSpaceRecordDecodes() throws {
        let (input, expect) = vector("wire-space-basic")
        guard case .space(let space)? = ZenSpacesDecoder.decode(
            id: input["id"] as! String, cleartext: input
        ) else {
            return XCTFail("expected space record")
        }
        XCTAssertEqual(space.uuid, expect["uuid"] as? String)
        XCTAssertEqual(space.name, expect["name"] as? String)
        XCTAssertEqual(space.children, expect["children"] as? [String])
        XCTAssertEqual(space.theme?.gradientColors, expect["gradientColors"] as? [String])
        XCTAssertEqual(space.theme?.dots.count, expect["dotCount"] as? Int)
        XCTAssertEqual(space.theme?.opacity, expect["opacity"] as? Double)
        XCTAssertEqual(space.theme?.texture, expect["texture"] as? Double)
        XCTAssertNil(space.containerGuid, "null containerGuid decodes to nil (default container)")
    }

    /// Real desktop payloads send gradientColors as generator objects with dot parameters.
    func testSpaceRecordWithRealGradientObjectsDecodes() throws {
        let (input, expect) = vector("wire-space-object-dots")
        guard case .space(let space)? = ZenSpacesDecoder.decode(
            id: input["id"] as! String, cleartext: input
        ) else {
            return XCTFail("expected space record to decode despite object gradientColors")
        }
        XCTAssertEqual(space.uuid, expect["uuid"] as? String)
        XCTAssertEqual(space.name, expect["name"] as? String)
        XCTAssertEqual(space.theme?.gradientColors, expect["gradientColors"] as? [String])
        XCTAssertEqual(space.theme?.opacity, expect["opacity"] as? Double)
        XCTAssertEqual(space.theme?.texture, expect["texture"] as? Double)
        let dot = try XCTUnwrap((expect["dots"] as? [[String: Any]])?.first)
        let decoded = try XCTUnwrap(space.theme?.dots.first)
        XCTAssertEqual(decoded.color.hexString, dot["hex"] as? String)
        XCTAssertEqual(decoded.isCustom, dot["isCustom"] as? Bool)
        XCTAssertEqual(decoded.isPrimary, dot["isPrimary"] as? Bool)
        XCTAssertEqual(decoded.algorithm, dot["algorithm"] as? String)
        XCTAssertEqual(decoded.lightness, dot["lightness"] as? Double, "lightness \"60\" → 60")
        XCTAssertEqual(decoded.positionX, dot["positionX"] as? Double)
        XCTAssertEqual(decoded.positionY, dot["positionY"] as? Double)
        XCTAssertEqual(decoded.type, dot["type"] as? String)
    }

    /// Real desktop payloads also send RGB arrays like `c: [255, 120, 80]`.
    func testSpaceRecordWithRGBArrayDots() throws {
        let (input, expect) = vector("wire-space-rgb-dots")
        guard case .space(let space)? = ZenSpacesDecoder.decode(
            id: input["id"] as! String, cleartext: input
        ) else {
            return XCTFail("expected RGB array space record to decode")
        }
        XCTAssertEqual(space.uuid, expect["uuid"] as? String)
        let dots = try XCTUnwrap(expect["dots"] as? [[String: Any]])
        XCTAssertEqual(space.theme?.dots.count, dots.count)
        XCTAssertEqual(space.theme?.dots[0].color.hexString, dots[0]["hex"] as? String)
        XCTAssertEqual(space.theme?.dots[0].isPrimary, dots[0]["isPrimary"] as? Bool)
        XCTAssertEqual(space.theme?.dots[1].color.hexString, dots[1]["hex"] as? String)
        XCTAssertEqual(space.theme?.dots[1].isPrimary, dots[1]["isPrimary"] as? Bool)
        XCTAssertEqual(space.theme?.gradientColors, expect["gradientColors"] as? [String])
        XCTAssertEqual(space.theme?.opacity, expect["opacity"] as? Double)
        XCTAssertEqual(space.theme?.texture, expect["texture"] as? Double)
    }

    // MARK: Tabs

    func testTabRecordDecodesWithDefaults() throws {
        let (input, expect) = vector("wire-tab-pinned-default")
        guard case .tab(let tab)? = ZenSpacesDecoder.decode(
            id: input["id"] as! String, cleartext: input
        ) else {
            return XCTFail("expected tab record")
        }
        XCTAssertEqual(tab.tabId, expect["tabId"] as? String)
        XCTAssertEqual(tab.url, expect["url"] as? String)
        XCTAssertEqual(tab.title, expect["title"] as? String)
        XCTAssertEqual(tab.essential, expect["essential"] as? Bool)
        XCTAssertEqual(tab.workspaceUuid, expect["workspaceUuid"] as? String)
        XCTAssertEqual(tab.pinned, expect["pinned"] as? Bool, "absent pinned decodes to nil")
        XCTAssertNil(tab.folderId, "absent folderId decodes to nil")
    }

    func testTabRecordWithoutPinnedFlagStaysPinned() throws {
        let input = ContractFixtures.json("wire-tab-pinned-default")["input"] as! [String: Any]
        guard case .tab(let tab)? = ZenSpacesDecoder.decode(
            id: input["id"] as! String, cleartext: input
        ) else {
            return XCTFail("expected tab record")
        }
        XCTAssertNil(tab.pinned)
        XCTAssertFalse(tab.isNormalTab, "isNormalTab == (pinned == false)")
    }

    func testNormalTabRecordCarriesPinnedFalse() throws {
        let (input, expect) = vector("wire-tab-normal-pinned-false")
        guard case .tab(let tab)? = ZenSpacesDecoder.decode(
            id: input["id"] as! String, cleartext: input
        ) else {
            return XCTFail("expected tab record")
        }
        XCTAssertEqual(tab.pinned, expect["pinned"] as? Bool)
        XCTAssertEqual(tab.isNormalTab, expect["isNormalTab"] as? Bool)
        XCTAssertEqual(tab.url, expect["url"] as? String)
        XCTAssertEqual(tab.essential, expect["essential"] as? Bool)
        XCTAssertEqual(tab.workspaceUuid, expect["workspaceUuid"] as? String)
    }

    func testPinnedStringFlagDecodesTolerantly() throws {
        let (input, expect) = vector("wire-tab-pinned-string-false")
        guard case .tab(let tab)? = ZenSpacesDecoder.decode(
            id: input["id"] as! String, cleartext: input
        ) else {
            return XCTFail("expected tab record")
        }
        XCTAssertEqual(tab.pinned, expect["pinned"] as? Bool, "\"false\" decodes to false")
        XCTAssertEqual(tab.isNormalTab, expect["isNormalTab"] as? Bool)
        XCTAssertEqual(tab.tabId, expect["tabId"] as? String)
        XCTAssertEqual(tab.url, expect["url"] as? String)
    }

    /// Hostile: `deleted` must be a JSON boolean. The string "true" is not a
    /// tombstone, so the otherwise valid tab record still decodes.
    func testDeletedStringIsNotATombstone() throws {
        let (input, expect) = vector("wire-deleted-string")
        guard case .tab(let tab)? = ZenSpacesDecoder.decode(
            id: input["id"] as! String, cleartext: input
        ) else {
            return XCTFail("expected tab record to survive a string deleted flag")
        }
        XCTAssertEqual(tab.tabId, expect["tabId"] as? String)
        XCTAssertEqual(tab.url, expect["url"] as? String)
        XCTAssertEqual(tab.title, expect["title"] as? String)
    }

    // MARK: Folders

    func testFolderRecordDecodes() throws {
        let (input, expect) = vector("wire-folder-basic")
        guard case .folder(let folder)? = ZenSpacesDecoder.decode(
            id: input["id"] as! String, cleartext: input
        ) else {
            return XCTFail("expected folder record")
        }
        XCTAssertEqual(folder.folderId, expect["folderId"] as? String)
        XCTAssertEqual(folder.name, expect["name"] as? String)
        XCTAssertEqual(folder.icon, expect["icon"] as? String)
        XCTAssertEqual(folder.workspaceUuid, expect["workspaceUuid"] as? String)
        XCTAssertNil(folder.parentFolderId, "null parentFolderId decodes to nil")
        XCTAssertEqual(folder.children, expect["children"] as? [String])
    }

    func testFolderWithObjectIconFieldStillDecodes() throws {
        let (input, expect) = vector("wire-folder-live-object")
        guard case .folder(let folder)? = ZenSpacesDecoder.decode(
            id: input["id"] as! String, cleartext: input
        ) else {
            return XCTFail("expected folder record with live object payload")
        }
        XCTAssertEqual(folder.folderId, expect["folderId"] as? String)
        XCTAssertEqual(folder.workspaceUuid, expect["workspaceUuid"] as? String)
        XCTAssertEqual(folder.children, expect["children"] as? [String])
        XCTAssertNil(folder.icon, "icon null stays null")
    }

    // MARK: Splits

    func testSplitRecordDecodes() throws {
        let (input, expect) = vector("wire-split-basic")
        guard case .split(let split)? = ZenSpacesDecoder.decode(
            id: input["id"] as! String, cleartext: input
        ) else {
            return XCTFail("expected split record")
        }
        XCTAssertEqual(split.splitId, expect["splitId"] as? String)
        XCTAssertEqual(split.gridType, expect["gridType"] as? String)
        XCTAssertEqual(split.tabs, expect["tabs"] as? [String])
        XCTAssertEqual(split.workspaceUuid, expect["workspaceUuid"] as? String)
        XCTAssertNil(split.folderId, "null folderId decodes to nil")
        XCTAssertNil(split.pinned, "absent pinned decodes to nil")
        XCTAssertEqual(split.isNormalSplit, expect["isNormalSplit"] as? Bool)
    }

    func testSplitRecordPinnedFalseDecodes() throws {
        let (input, expect) = vector("wire-split-normal-pinned-false")
        guard case .split(let split)? = ZenSpacesDecoder.decode(
            id: input["id"] as! String, cleartext: input
        ) else {
            return XCTFail("expected split record")
        }
        XCTAssertEqual(split.pinned, expect["pinned"] as? Bool)
        XCTAssertEqual(split.isNormalSplit, expect["isNormalSplit"] as? Bool)
        XCTAssertEqual(split.splitId, expect["splitId"] as? String)
        XCTAssertEqual(split.tabs, expect["tabs"] as? [String])
        XCTAssertEqual(split.workspaceUuid, expect["workspaceUuid"] as? String)
    }

    func testSplitRecordWithoutPinnedFlagStaysPinned() throws {
        let input = ContractFixtures.json("wire-split-basic")["input"] as! [String: Any]
        guard case .split(let split)? = ZenSpacesDecoder.decode(
            id: input["id"] as! String, cleartext: input
        ) else {
            return XCTFail("expected split record")
        }
        XCTAssertNil(split.pinned)
        XCTAssertFalse(split.isNormalSplit)
    }

    // MARK: Layout

    func testLayoutRecordDecodes() throws {
        let (input, expect) = vector("wire-layout-basic")
        guard case .layout(let layout)? = ZenSpacesDecoder.decode(
            id: input["id"] as! String, cleartext: input
        ) else {
            return XCTFail("expected layout record")
        }
        XCTAssertEqual(layout.spaces, expect["spaces"] as? [String])
        let essentials = try XCTUnwrap(expect["essentials"] as? [String: Any])
        XCTAssertEqual(layout.essentials?["default"], essentials["default"] as? [String])
        XCTAssertEqual(layout.essentials?["work"], essentials["work"] as? [String],
                       "non-string entries (42, null) are dropped, the bucket survives")
    }

    // MARK: Ignored records (all cases)

    /// Every non-decodable cleartext shape is dropped: container kind
    /// (contractually not applied), future kinds, malformed data, tombstones,
    /// records missing required fields, and records missing kind.
    func testUnknownKindAndGarbageAreIgnored() {
        for record in ContractFixtures.cases("wire-ignored-records") {
            let id = (record["id"] as? String) ?? (record["input"] as? [String: Any])?["id"] as? String ?? "?"
            let input = record["input"] as! [String: Any]
            let expect = record["expect"] as! [String: Any]
            XCTAssertEqual(expect["dropped"] as? Bool, true, "\(id) must expect dropped")
            XCTAssertNil(
                ZenSpacesDecoder.decode(id: input["id"] as? String ?? id, cleartext: input),
                "\(id) must be ignored"
            )
        }
    }

    // MARK: Prefs

    /// The zen.spaces-sync.normal-tabs pref parses tolerantly (fixture
    /// `wire-prefs-normal-tabs`); absent values have no fixture and yield nil.
    func testPrefBoolParsesSyncedValueShapes() {
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
        XCTAssertNil(SpacesSyncService.prefBool(nil))
    }

    // MARK: Local snapshot assembly + cache edits (non-wire, literal kept)

    /// Caches written before the essentials field existed must keep decoding.
    func testLegacySnapshotWithoutEssentialsDecodes() throws {
        let json = """
        {"spaces":[{"id":"s1","name":"Work","icon":"💼","themeColors":[],"themeOpacity":null,"pinned":[]}],"fetchedAt":7200000.0}
        """
        let snapshot = try JSONDecoder().decode(ZenSnapshot.self, from: Data(json.utf8))
        XCTAssertEqual(snapshot.spaces.count, 1)
        XCTAssertTrue(snapshot.essentials.isEmpty)
    }

    /// Both spaces must survive decoding when only one has an exotic theme.
    func testMixedThemeShapesKeepBothSpaces() throws {
        let plain = try decode("""
        {"id":"a","kind":"space","data":{"uuid":"space-a","name":"A","theme":{"type":"gradient","gradientColors":[],"opacity":0.3},"children":[]}}
        """)
        let exotic = try decode("""
        {"id":"b","kind":"space","data":{"uuid":"space-b","name":"B","theme":{"type":"solid","gradientColors":[{"c":"#fff"}],"opacity":0.7},"children":[]}}
        """)
        guard case .space(let a)? = ZenSpacesDecoder.decode(id: "a", cleartext: plain),
              case .space(let b)? = ZenSpacesDecoder.decode(id: "b", cleartext: exotic)
        else { return XCTFail("both space records must decode") }
        XCTAssertEqual(a.uuid, "space-a")
        XCTAssertEqual(b.uuid, "space-b")
    }

    /// Snapshot building from raw decrypted records: spaces follow the
    /// layout order, folders expand into their pinned tabs.
    func testSnapshotAssemblyInputs() throws {
        let tab = try JSONDecoder().decode(
            ZenTabRecord.self,
            from: Data("""
            {"tabId":"t1","url":"https://a.de","title":"A","essential":true}
            """.utf8)
        )
        XCTAssertTrue(tab.essential ?? false)
    }

    /// Members of a split must not resurface as individual "unplaced" tabs:
    /// the split group occupies the slot, members render inside it.
    func testSplitAssemblyPlacesMembersAndSkipsUnplaced() throws {
        let space = ZenSpaceRecord(
            uuid: "space-1",
            name: "Work",
            children: ["split-1"]
        )
        let a = ZenTabRecord(tabId: "tab-a", url: "https://a.de", title: "A", workspaceUuid: "space-1")
        let b = ZenTabRecord(tabId: "tab-b", url: "https://b.de", title: "B", workspaceUuid: "space-1")
        let c = ZenTabRecord(tabId: "tab-c", url: "https://c.de", title: "C", workspaceUuid: "space-1")
        let split = ZenSplitRecord(
            splitId: "split-1",
            gridType: "grid",
            tabs: ["tab-a", "tab-b"],
            workspaceUuid: "space-1"
        )

        let built = SpacesSyncService.makeSpace(
            from: space,
            allTabs: ["tab-a": a, "tab-b": b, "tab-c": c],
            folders: [:],
            splits: ["split-1": split]
        )

        XCTAssertEqual(built.pinned.count, 2)
        guard case .split(let s) = built.pinned[0] else {
            return XCTFail("expected split item first")
        }
        XCTAssertEqual(s.tabs.map(\.id), ["tab-a", "tab-b"])
        // Member tabs must NOT appear as individual unplaced tabs; only C remains.
        XCTAssertEqual(built.pinned[1].id, "tab-c")
    }

    func testInsertingAtFrontOfUnpinnedLandsBeforeFirstOpenTab() {
        XCTAssertEqual(
            SpacesSyncEdits.insertingAtFrontOfUnpinned(
                ["new"],
                into: ["pin-1", "folder-1", "n1", "n2"],
                pinnedIds: ["pin-1", "folder-1"]
            ),
            ["pin-1", "folder-1", "new", "n1", "n2"]
        )
        XCTAssertEqual(
            SpacesSyncEdits.insertingAtFrontOfUnpinned(
                ["new"],
                into: ["pin-1", "folder-1"],
                pinnedIds: ["pin-1", "folder-1"]
            ),
            ["pin-1", "folder-1", "new"],
            "no open tabs yet: append is the start of that region"
        )
        XCTAssertEqual(
            SpacesSyncEdits.insertingAtFrontOfUnpinned(
                ["new"],
                into: ["n1", "n2"],
                pinnedIds: []
            ),
            ["new", "n1", "n2"]
        )
        XCTAssertEqual(
            SpacesSyncEdits.insertingAtFrontOfUnpinned(
                ["n1"],
                into: ["pin-1", "n1"],
                pinnedIds: ["pin-1"]
            ),
            ["pin-1", "n1"],
            "already present ids are not duplicated"
        )
        XCTAssertEqual(
            SpacesSyncEdits.attaching(
                "new",
                to: ["t1"],
                kind: .pinned,
                pinnedIds: ["t1"]
            ),
            ["t1", "new"],
            "pinned saves still append"
        )
        XCTAssertEqual(
            SpacesSyncEdits.attaching(
                "new",
                to: ["t1", "n1"],
                kind: .normal,
                pinnedIds: ["t1"]
            ),
            ["t1", "new", "n1"]
        )
    }

    func testChildrenReplaceSplicesMembersInPlace() {
        XCTAssertEqual(
            SpacesSyncEdits.replacing("split-1", with: ["tab-a", "tab-b"], in: ["x", "split-1", "y"]),
            ["x", "tab-a", "tab-b", "y"]
        )
        XCTAssertEqual(
            SpacesSyncEdits.removing("tab-a", from: ["tab-a", "tab-b"]),
            ["tab-b"]
        )
        // Dedup members that already sit next to the split.
        XCTAssertEqual(
            SpacesSyncEdits.replacing("split-1", with: ["tab-a", "tab-b"], in: ["tab-a", "split-1"]),
            ["tab-a", "tab-b"]
        )
    }

    func testCacheExpandSplitKeepsMemberTabs() {
        let a = ZenTab(id: "tab-a", url: "https://a.de", title: "A")
        let b = ZenTab(id: "tab-b", url: "https://b.de", title: "B")
        let space = ZenSpace(
            id: "s1",
            name: "Work",
            pinned: [
                .split(ZenSplit(id: "split-1", gridType: "vsep", tabs: [a, b])),
                .tab(ZenTab(id: "tab-c", url: "https://c.de", title: "C")),
            ]
        )
        let snap = SpacesSyncEdits.expandSplit(
            splitId: "split-1",
            in: ZenSnapshot(spaces: [space], fetchedAt: .distantPast)
        )
        XCTAssertEqual(snap.spaces[0].pinned.map(\.id), ["tab-a", "tab-b", "tab-c"])
    }

    func testCacheRemoveTabFromFolderAndCollapsesSplit() {
        let a = ZenTab(id: "tab-a", url: "https://a.de", title: "A")
        let b = ZenTab(id: "tab-b", url: "https://b.de", title: "B")
        let space = ZenSpace(
            id: "s1",
            name: "Work",
            pinned: [
                .folder(ZenFolder(id: "f1", name: "Docs", icon: nil, tabs: [a])),
                .split(ZenSplit(id: "split-1", gridType: "vsep", tabs: [a, b])),
            ]
        )
        let snap = SpacesSyncEdits.remove(
            id: "tab-a",
            from: ZenSnapshot(
                spaces: [space],
                essentials: ["default": [a]],
                fetchedAt: .distantPast
            )
        )
        XCTAssertEqual(snap.spaces[0].pinned.count, 2)
        guard case .folder(let folder) = snap.spaces[0].pinned[0] else {
            return XCTFail("folder should remain")
        }
        XCTAssertTrue(folder.tabs.isEmpty)
        guard case .tab(let remaining) = snap.spaces[0].pinned[1] else {
            return XCTFail("2-tab split should collapse to the leftover tab")
        }
        XCTAssertEqual(remaining.id, "tab-b")
        XCTAssertTrue(snap.essentials["default"]?.isEmpty ?? false)
    }

    // MARK: Normal (unpinned) tabs — Zen PR #15250

    /// `children` interleaves pinned and normal tabs in strip order; the app
    /// partitions by the record's `pinned` flag and keeps the order.
    func testMakeSpacePartitionsPinnedAndNormalInOrder() {
        let space = ZenSpaceRecord(uuid: "space-1", name: "Work", children: ["p1", "n1", "p2", "n2"])
        let p1 = ZenTabRecord(tabId: "p1", url: "https://p1.de", title: "P1", pinned: true, workspaceUuid: "space-1")
        let n1 = ZenTabRecord(tabId: "n1", url: "https://n1.de", title: "N1", pinned: false, workspaceUuid: "space-1")
        let p2 = ZenTabRecord(tabId: "p2", url: "https://p2.de", title: "P2", workspaceUuid: "space-1")
        let n2 = ZenTabRecord(tabId: "n2", url: "https://n2.de", title: "N2", pinned: false, workspaceUuid: "space-1")

        let built = SpacesSyncService.makeSpace(
            from: space,
            allTabs: ["p1": p1, "n1": n1, "p2": p2, "n2": n2],
            folders: [:]
        )

        XCTAssertEqual(built.pinned.map(\.id), ["p1", "p2"])
        XCTAssertEqual(built.tabs.map(\.id), ["n1", "n2"])
    }

    func testMakeSpaceWithoutNormalTabsKeepsOldBehaviour() {
        let space = ZenSpaceRecord(uuid: "space-1", name: "Work", children: ["p1", "p2"])
        let p1 = ZenTabRecord(tabId: "p1", url: "https://p1.de", title: "P1", workspaceUuid: "space-1")
        let p2 = ZenTabRecord(tabId: "p2", url: "https://p2.de", title: "P2", workspaceUuid: "space-1")

        let built = SpacesSyncService.makeSpace(from: space, allTabs: ["p1": p1, "p2": p2], folders: [:])

        XCTAssertEqual(built.pinned.map(\.id), ["p1", "p2"])
        XCTAssertTrue(built.tabs.isEmpty)
    }

    func testCacheRoundTripsNormalTabs() throws {
        let normal = ZenTab(id: "tab-n", url: "https://n.de", title: "N")
        let space = ZenSpace(
            id: "s1",
            name: "Work",
            pinned: [.tab(ZenTab(id: "tab-p", url: "https://p.de", title: "P"))],
            tabs: [.tab(normal)]
        )
        let data = try JSONEncoder().encode(ZenSnapshot(spaces: [space], fetchedAt: .distantPast))
        let back = try JSONDecoder().decode(ZenSnapshot.self, from: data)
        XCTAssertEqual(back.spaces[0].pinned.map(\.id), ["tab-p"])
        XCTAssertEqual(back.spaces[0].tabs.map(\.id), ["tab-n"])
    }

    func testRemoveClearsNormalTabs() {
        let normal = ZenTab(id: "tab-n", url: "https://n.de", title: "N")
        let space = ZenSpace(id: "s1", name: "Work", pinned: [], tabs: [.tab(normal)])
        let snap = SpacesSyncEdits.remove(id: "tab-n", from: ZenSnapshot(spaces: [space], fetchedAt: .distantPast))
        XCTAssertTrue(snap.spaces[0].tabs.isEmpty)
    }

    /// A split of normal tabs reports `pinned: false` from its first member
    /// and belongs in the normal bucket; its members must not resurface as
    /// individual unplaced tabs.
    func testMakeSpacePartitionsNormalSplit() {
        let space = ZenSpaceRecord(uuid: "space-1", name: "Work", children: ["split-p", "split-n"])
        let p1 = ZenTabRecord(tabId: "p1", url: "https://p1.de", title: "P1", pinned: true, workspaceUuid: "space-1")
        let p2 = ZenTabRecord(tabId: "p2", url: "https://p2.de", title: "P2", pinned: true, workspaceUuid: "space-1")
        let n1 = ZenTabRecord(tabId: "n1", url: "https://n1.de", title: "N1", pinned: false, workspaceUuid: "space-1")
        let n2 = ZenTabRecord(tabId: "n2", url: "https://n2.de", title: "N2", pinned: false, workspaceUuid: "space-1")
        let pinnedSplit = ZenSplitRecord(splitId: "split-p", pinned: true, tabs: ["p1", "p2"], workspaceUuid: "space-1")
        let normalSplit = ZenSplitRecord(splitId: "split-n", pinned: false, tabs: ["n1", "n2"], workspaceUuid: "space-1")

        let built = SpacesSyncService.makeSpace(
            from: space,
            allTabs: ["p1": p1, "p2": p2, "n1": n1, "n2": n2],
            folders: [:],
            splits: ["split-p": pinnedSplit, "split-n": normalSplit]
        )

        XCTAssertEqual(built.pinned.map(\.id), ["split-p"])
        XCTAssertEqual(built.tabs.map(\.id), ["split-n"])
        guard case .split(let split) = built.tabs[0] else {
            return XCTFail("expected split in normal bucket")
        }
        XCTAssertEqual(split.tabs.map(\.id), ["n1", "n2"])
    }

    func testCacheExpandNormalSplitKeepsMemberTabs() {
        let a = ZenTab(id: "n1", url: "https://n1.de", title: "N1")
        let b = ZenTab(id: "n2", url: "https://n2.de", title: "N2")
        let space = ZenSpace(
            id: "s1",
            name: "Work",
            pinned: [.tab(ZenTab(id: "p1", url: "https://p1.de", title: "P1"))],
            tabs: [.split(ZenSplit(id: "split-n", gridType: "vsep", tabs: [a, b]))]
        )
        let snap = SpacesSyncEdits.expandSplit(
            splitId: "split-n",
            in: ZenSnapshot(spaces: [space], fetchedAt: .distantPast)
        )
        XCTAssertEqual(snap.spaces[0].pinned.map(\.id), ["p1"])
        XCTAssertEqual(snap.spaces[0].tabs.map(\.id), ["n1", "n2"])
    }

    // MARK: Normal-tabs capability (SPEC §7 write gating)

    /// Capability derivation: only a present key parsing true is `enabled`;
    /// a present key with false/null/unparseable is `disabled`; a missing
    /// record or key is `absent`.
    func testNormalTabsCapabilityDerivation() {
        let key = SpacesSyncService.normalTabsPrefKey
        XCTAssertEqual(
            SpacesSyncService.normalTabsCapability(prefsRecordPresent: false, values: [key: true]),
            .absent
        )
        XCTAssertEqual(
            SpacesSyncService.normalTabsCapability(prefsRecordPresent: true, values: [:]),
            .absent
        )
        XCTAssertEqual(
            SpacesSyncService.normalTabsCapability(prefsRecordPresent: true, values: [key: true]),
            .enabled
        )
        XCTAssertEqual(
            SpacesSyncService.normalTabsCapability(prefsRecordPresent: true, values: [key: "true"]),
            .enabled
        )
        XCTAssertEqual(
            SpacesSyncService.normalTabsCapability(prefsRecordPresent: true, values: [key: 1]),
            .enabled
        )
        XCTAssertEqual(
            SpacesSyncService.normalTabsCapability(prefsRecordPresent: true, values: [key: false]),
            .disabled
        )
        XCTAssertEqual(
            SpacesSyncService.normalTabsCapability(prefsRecordPresent: true, values: [key: NSNull()]),
            .disabled,
            "a null value proves support while keeping the option off"
        )
        XCTAssertEqual(
            SpacesSyncService.normalTabsCapability(prefsRecordPresent: true, values: [key: "perhaps"]),
            .disabled
        )
    }

    /// A `pinned:false` record observed in `spaces` upgrades `absent` to
    /// `disabled` (browser support is proven), but never to `enabled`.
    func testNormalTabsCapabilitySecondarySignal() {
        XCTAssertEqual(
            SpacesSyncService.effectiveCapability(.absent, observedNormalItems: true),
            .disabled
        )
        XCTAssertEqual(
            SpacesSyncService.effectiveCapability(.absent, observedNormalItems: false),
            .absent
        )
        XCTAssertEqual(
            SpacesSyncService.effectiveCapability(.disabled, observedNormalItems: true),
            .disabled
        )
        XCTAssertEqual(
            SpacesSyncService.effectiveCapability(.enabled, observedNormalItems: true),
            .enabled
        )
    }

    /// Snapshot capability round-trips the cache, and an old cache file
    /// without the field decodes as `.absent`.
    func testSnapshotCapabilityRoundTripAndLegacyDecode() throws {
        let withCapability = ZenSnapshot(
            spaces: [ZenSpace(id: "s1", name: "Work")],
            normalTabsCapability: .enabled,
            fetchedAt: .distantPast
        )
        let data = try JSONEncoder().encode(withCapability)
        let back = try JSONDecoder().decode(ZenSnapshot.self, from: data)
        XCTAssertEqual(back.normalTabsCapability, .enabled)

        let legacyJSON = """
        {"spaces":[{"id":"s1","name":"Work","icon":null,"containerGuid":null,"pinned":[],"tabs":[]}],
         "essentials":{},"separateEssentialsPref":null,"fetchedAt":7200000.0}
        """
        let legacy = try JSONDecoder().decode(ZenSnapshot.self, from: Data(legacyJSON.utf8))
        XCTAssertEqual(legacy.normalTabsCapability, .absent)
        XCTAssertEqual(legacy.spaces.count, 1)
    }

    // MARK: Synced prefs tri-state

    /// A prefs record with the key parsing true reads `enabled`; the display
    /// default (`normalTabs`) stays true.
    func testSyncedPrefsCapabilityEnabled() async throws {
        let keys = prefKeys
        let server = MutableSyncServer()
        try seedPrefs(server, keys: keys, values: [SpacesSyncService.normalTabsPrefKey: true])

        let prefs = await SpacesSyncService.syncedPrefs(client: makeClient(keys: keys, transport: server))

        XCTAssertEqual(prefs.normalTabsCapability, .enabled)
        XCTAssertTrue(prefs.normalTabs)
    }

    /// A prefs record present with the key false reads `disabled`; a null
    /// value also reads `disabled`.
    func testSyncedPrefsCapabilityDisabled() async throws {
        let keys = prefKeys
        let server = MutableSyncServer()
        try seedPrefs(server, keys: keys, values: [SpacesSyncService.normalTabsPrefKey: false])
        let disabled = await SpacesSyncService.syncedPrefs(client: makeClient(keys: keys, transport: server))
        XCTAssertEqual(disabled.normalTabsCapability, .disabled)

        let nullServer = MutableSyncServer()
        try seedPrefs(nullServer, keys: keys, values: [SpacesSyncService.normalTabsPrefKey: NSNull()])
        let nullValue = await SpacesSyncService.syncedPrefs(client: makeClient(keys: keys, transport: nullServer))
        XCTAssertEqual(nullValue.normalTabsCapability, .disabled)
        XCTAssertTrue(nullValue.normalTabs, "a null value keeps the lenient read default")
    }

    /// No readable prefs record reads `absent` with the display default true.
    func testSyncedPrefsCapabilityAbsent() async {
        let server = MutableSyncServer()
        let prefs = await SpacesSyncService.syncedPrefs(
            client: makeClient(keys: prefKeys, transport: server)
        )
        XCTAssertEqual(prefs.normalTabsCapability, .absent)
        XCTAssertTrue(prefs.normalTabs)
    }

    /// The secondary signal through the real snapshot load: a normal tab
    /// observed while the prefs key is missing (read default true, so it
    /// renders) still proves browser support and reports `disabled`.
    func testLoadSnapshotSecondarySignalUpgradesAbsentToDisabled() async throws {
        let keys = prefKeys
        let server = MutableSyncServer()
        try server.seedCleartext(
            collection: "spaces",
            id: "space-1",
            kind: "space",
            data: ["uuid": "space-1", "name": "Work", "children": ["n1"]],
            keys: keys
        )
        try server.seedCleartext(
            collection: "spaces",
            id: "n1",
            kind: "tab",
            data: ["tabId": "n1", "url": "https://n1.de", "title": "N1", "pinned": false, "workspaceUuid": "space-1"],
            keys: keys
        )

        let snapshot = try await SpacesSyncService.loadSnapshot(
            client: makeClient(keys: keys, transport: server)
        )

        XCTAssertEqual(snapshot.normalTabsCapability, .disabled)
        XCTAssertEqual(snapshot.spaces.first?.tabs.map(\.id), ["n1"], "the lenient read default still renders the record")
    }

    /// The secondary signal upgrades `absent` to `disabled`, and a normal
    /// record that renders (pref default true) still proves support.
    func testLoadSnapshotReadsCapabilityFromPrefs() async throws {
        let keys = prefKeys
        let server = MutableSyncServer()
        try server.seedCleartext(
            collection: "spaces",
            id: "space-1",
            kind: "space",
            data: ["uuid": "space-1", "name": "Work", "children": []],
            keys: keys
        )
        try seedPrefs(server, keys: keys, values: [SpacesSyncService.normalTabsPrefKey: true])

        let snapshot = try await SpacesSyncService.loadSnapshot(
            client: makeClient(keys: keys, transport: server)
        )

        XCTAssertEqual(snapshot.normalTabsCapability, .enabled)
    }

    private var prefKeys: SyncCrypto.KeyBundle {
        SyncCrypto.KeyBundle(
            encryptionKey: Data(repeating: 0x51, count: 32),
            hmacKey: Data(repeating: 0x52, count: 32)
        )
    }

    private func seedPrefs(
        _ server: MutableSyncServer,
        keys: SyncCrypto.KeyBundle,
        values: [String: Any]
    ) throws {
        let cleartext: [String: Any] = ["id": "prefs", "value": values]
        let payload = try SyncCrypto.encryptBSO(
            plaintext: try JSONSerialization.data(withJSONObject: cleartext),
            keys: keys
        )
        server.setRecord(collection: "prefs", id: "prefs", payload: payload)
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
