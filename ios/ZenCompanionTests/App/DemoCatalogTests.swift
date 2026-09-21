import XCTest
@testable import ZenCompanion

final class DemoCatalogTests: XCTestCase {
    func testSnapshotCoversCoreSurfaces() {
        let snap = DemoCatalog.snapshot
        XCTAssertGreaterThanOrEqual(snap.spaces.count, 3)
        XCTAssertTrue(snap.spaces.contains { !$0.pinned.isEmpty })
        XCTAssertTrue(snap.spaces.contains { !$0.tabs.isEmpty })
        XCTAssertTrue(snap.spaces.contains { space in
            space.pinned.contains { item in
                if case .folder = item { return true }
                return false
            }
        })
        XCTAssertTrue(snap.spaces.contains { space in
            space.pinned.contains { item in
                if case .split = item { return true }
                return false
            }
        })
        XCTAssertEqual(snap.essentials.count, snap.spaces.count)
        XCTAssertTrue(snap.spaces.contains { space in
            space.pinned.contains { item in
                if case .folder(let folder) = item {
                    return !(folder.subfolders ?? []).isEmpty
                }
                return false
            }
        })
    }

    func testActivityHasHistory() {
        XCTAssertFalse(DemoCatalog.activity.history.isEmpty)
    }

    func testLegacyAccountDecodesWithoutDemoFlag() throws {
        let json = """
        {"email":"a@b.c","uid":"u","sessionTokenHex":"aa","kBHex":"bb"}
        """.data(using: .utf8)!
        let snap = try JSONDecoder().decode(AccountSnapshot.self, from: json)
        XCTAssertFalse(snap.isDemo)
        XCTAssertEqual(snap.email, "a@b.c")
    }

    func testDemoAccountRoundTrips() throws {
        let data = try JSONEncoder().encode(DemoCatalog.account)
        let snap = try JSONDecoder().decode(AccountSnapshot.self, from: data)
        XCTAssertTrue(snap.isDemo)
        XCTAssertEqual(snap.uid, "demo")
    }
}

final class FxASocialAuthTests: XCTestCase {
    func testBlocksAppleAndGoogleAuthHosts() {
        XCTAssertTrue(FxASocialAuth.shouldBlock(URL(string: "https://appleid.apple.com/auth/authorize")!))
        XCTAssertTrue(FxASocialAuth.shouldBlock(URL(string: "https://accounts.google.com/o/oauth2/v2/auth")!))
    }

    func testAllowsMozillaAccounts() {
        XCTAssertFalse(FxASocialAuth.shouldBlock(URL(string: "https://accounts.firefox.com/?service=sync")!))
        XCTAssertFalse(FxASocialAuth.shouldBlock(URL(string: "https://api.accounts.firefox.com/v1/account/status")!))
    }
}
