import XCTest
@testable import ZenCompanion

final class AccountStoreTests: XCTestCase {
    private var secure: FakeSecureStore!
    private var files: FakeFileStore!

    private var sessionURL: URL { AppGroup.container.appendingPathComponent("account-session.json") }
    private var legacyURL: URL { AppGroup.container.appendingPathComponent("account.json") }
    private var legacyCredsURL: URL { AppGroup.container.appendingPathComponent("token-creds.json") }

    private var sample: AccountSnapshot {
        AccountSnapshot(email: "a@b.c", uid: "u1", sessionTokenHex: "aa", kBHex: "bb")
    }

    override func setUp() {
        super.setUp()
        secure = FakeSecureStore()
        files = FakeFileStore()
        AccountStore.secureStore = secure
        AccountStore.fileStore = files
        AccountStore.clear()
        secure.deleteCount = 0
        files.removeCount = 0
    }

    override func tearDown() {
        AccountStore.clear()
        super.tearDown()
    }

    func testSaveWritesKeychainOnlyAndNoFile() throws {
        try AccountStore.save(sample)

        XCTAssertNotNil(secure.stored)
        XCTAssertEqual(secure.writeCount, 1)
        XCTAssertTrue(files.storage.isEmpty)
        XCTAssertEqual(files.writeCount, 0)
    }

    func testSaveThrowsWhenKeychainWriteFails() {
        secure.writeResult = false

        XCTAssertThrowsError(try AccountStore.save(sample)) { error in
            guard let sync = error as? SyncError, case .storageUnavailable = sync else {
                return XCTFail("expected SyncError.storageUnavailable, got \(error)")
            }
        }
        XCTAssertNil(secure.stored)
        XCTAssertTrue(files.storage.isEmpty)
    }

    func testLoadPrefersKeychainOverLegacyFile() throws {
        secure.stored = try JSONEncoder().encode(sample)
        let legacy = AccountSnapshot(email: "legacy@b.c", uid: "old", sessionTokenHex: "cc", kBHex: "dd")
        files.storage[sessionURL] = try JSONEncoder().encode(legacy)

        let loaded = AccountStore.load()

        XCTAssertEqual(loaded?.uid, "u1")
        XCTAssertNil(files.storage[sessionURL])
    }

    func testLoadImportsSessionBackupOnceAndDeletesIt() throws {
        files.storage[sessionURL] = try JSONEncoder().encode(sample)

        let loaded = AccountStore.load()

        XCTAssertEqual(loaded?.uid, "u1")
        XCTAssertEqual(secure.writeCount, 1)
        XCTAssertNil(files.storage[sessionURL])

        _ = AccountStore.load()
        XCTAssertEqual(secure.writeCount, 1)
    }

    func testLoadImportsLegacyAccountFileOnce() throws {
        files.storage[legacyURL] = try JSONEncoder().encode(sample)

        let loaded = AccountStore.load()

        XCTAssertEqual(loaded?.uid, "u1")
        XCTAssertEqual(secure.writeCount, 1)
        XCTAssertNil(files.storage[legacyURL])
    }

    func testKeychainNotFoundAndNoFilesReturnsNil() {
        XCTAssertNil(AccountStore.load())
    }

    func testTransientKeychainErrorDoesNotDeleteLegacyFile() throws {
        files.storage[sessionURL] = try JSONEncoder().encode(sample)
        secure.transientFailure = true

        XCTAssertNil(AccountStore.load())

        XCTAssertNotNil(files.storage[sessionURL])
        XCTAssertNil(secure.stored)
    }

    func testClearDeletesKeychainAndLegacyFiles() throws {
        secure.stored = try JSONEncoder().encode(sample)
        files.storage[sessionURL] = Data("session".utf8)
        files.storage[legacyURL] = Data("legacy".utf8)
        files.storage[legacyCredsURL] = Data("creds".utf8)

        AccountStore.clear()

        XCTAssertNil(secure.stored)
        XCTAssertTrue(files.storage.isEmpty)
    }

    func testCorruptKeychainDataReturnsNil() {
        secure.stored = Data("not json".utf8)

        XCTAssertNil(AccountStore.load())
    }

    func testIsSignedInFollowsLoad() throws {
        secure.stored = try JSONEncoder().encode(sample)
        XCTAssertTrue(AccountStore.isSignedIn)

        AccountStore.clear()
        XCTAssertFalse(AccountStore.isSignedIn)
    }

    func testFailedLegacyMigrationDeletesPlaintextAndReturnsNil() throws {
        files.storage[sessionURL] = try JSONEncoder().encode(sample)
        secure.writeResult = false

        XCTAssertNil(AccountStore.load())

        XCTAssertTrue(files.storage.isEmpty)
        XCTAssertNil(secure.stored)
    }
}

private final class FakeSecureStore: AccountSecureStore {
    var stored: Data?
    var writeResult = true
    var transientFailure = false
    var writeCount = 0
    var deleteCount = 0

    var lastReadWasTransientFailure: Bool { transientFailure }

    func read() -> Data? { stored }

    func write(_ data: Data) -> Bool {
        writeCount += 1
        guard writeResult else { return false }
        stored = data
        return true
    }

    func delete() {
        deleteCount += 1
        stored = nil
    }
}

private final class FakeFileStore: AccountFileStore {
    var storage: [URL: Data] = [:]
    var writeCount = 0
    var removeCount = 0

    func read(_ url: URL) -> Data? { storage[url] }

    func write(_ data: Data, to url: URL) throws {
        writeCount += 1
        storage[url] = data
    }

    func remove(_ url: URL) {
        removeCount += 1
        storage.removeValue(forKey: url)
    }

    func exists(_ url: URL) -> Bool { storage[url] != nil }
}
