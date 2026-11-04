import Foundation
import Security
import os

/// Injectable seam for Keychain access. The default implementation preserves
/// the previous `Security` behavior exactly.
protocol AccountSecureStore {
    func read() -> Data?
    func write(_ data: Data) -> Bool
    func delete()
    /// True when the most recent `read()` failed for a transient reason
    /// (any OSStatus other than `errSecItemNotFound`).
    var lastReadWasTransientFailure: Bool { get }
}

extension AccountSecureStore {
    var lastReadWasTransientFailure: Bool { false }
}

/// Injectable seam for plaintext file access used only for legacy migration
/// and cleanup. The default implementation preserves the previous
/// `FileManager` behavior exactly.
protocol AccountFileStore {
    func read(_ url: URL) -> Data?
    func write(_ data: Data, to url: URL) throws
    func remove(_ url: URL)
    func exists(_ url: URL) -> Bool
}

/// Secrets live exclusively in the Keychain (AfterFirstUnlockThisDeviceOnly)
/// shared with the extensions via the keychain-access-group. Plaintext files
/// are never written; existing legacy files are migrated once and deleted.
enum AccountStore {
    /// Replaceable in tests; production uses the concrete stores below.
    static var secureStore: AccountSecureStore = KeychainSecureStore()
    static var fileStore: AccountFileStore = DefaultAccountFileStore()
    /// Replaceable in tests; production uses the real URLSession transport.
    static var transport: SyncHTTPTransport = URLSessionTransport.shared

    private static let lock = NSLock()
    private static var cachedSnapshot: AccountSnapshot?
    private static var cachedCreds: TokenServerCreds?
    /// Bumped by `save` and `clear`. Work that started under an older
    /// generation (a credential fetch, a refresh) must not publish its result,
    /// so a sign-out can't be undone by a request that was already in flight.
    private static var generation = 0

    private static var sessionBackupURL: URL { AppGroup.container.appendingPathComponent("account-session.json") }
    private static var legacyFileURL: URL { AppGroup.container.appendingPathComponent("account.json") }
    private static var legacyCredsURL: URL { AppGroup.container.appendingPathComponent("token-creds.json") }

    static func save(_ snapshot: AccountSnapshot) throws {
        let data = try JSONEncoder().encode(snapshot)

        // Keychain only — plaintext files are never written.
        guard secureStore.write(data) else {
            os_log("AccountStore: keychain write failed", log: .default, type: .error)
            throw SyncError.storageUnavailable
        }

        lock.lock()
        generation += 1
        cachedCreds = nil
        lock.unlock()
        storeInMemory(snapshot)
    }

    static func republishForShareExtension() {
        guard let snapshot = load() else { return }
        try? save(snapshot)
    }

    static func load() -> AccountSnapshot? {
        lock.lock()
        let memory = cachedSnapshot
        lock.unlock()
        if let memory { return memory }

        // 1. Keychain.
        if let data = secureStore.read() {
            guard let snapshot = try? JSONDecoder().decode(AccountSnapshot.self, from: data) else {
                // Corrupt keychain payload: fail closed rather than trust it.
                return nil
            }
            storeInMemory(snapshot)
            scrubLegacyFiles()
            return snapshot
        }
        if secureStore.lastReadWasTransientFailure {
            // Transient Keychain error: do not touch the legacy files yet.
            os_log("AccountStore: keychain read failed transiently; legacy files kept", log: .default, type: .error)
            return nil
        }

        // 2. One-time legacy migration: account-session.json, then account.json.
        for url in [sessionBackupURL, legacyFileURL] {
            guard fileStore.exists(url), let data = fileStore.read(url) else { continue }
            guard let snapshot = try? JSONDecoder().decode(AccountSnapshot.self, from: data) else {
                // Undecodable plaintext secret: remove it and try the next source.
                fileStore.remove(url)
                continue
            }
            if secureStore.write(data) {
                scrubLegacyFiles()
                storeInMemory(snapshot)
                return snapshot
            }
            // Fail closed: a plaintext secret must not survive an unsecured migration.
            scrubLegacyFiles()
            return nil
        }

        return nil
    }

    static func clear() {
        lock.lock()
        generation += 1
        cachedSnapshot = nil
        cachedCreds = nil
        lock.unlock()
        scrubLegacyFiles()
        secureStore.delete()
        AppGroup.defaults.removeObject(forKey: "signedInEmail")
    }

    static var isSignedIn: Bool { load() != nil }

    static var isDemo: Bool { load()?.isDemo == true }

    static func connect() async throws -> SyncClient {
        let started = currentGeneration
        guard let account = load(), !account.isDemo else { throw SyncError.notSignedIn }
        let kB = try FxACrypto.unhex(account.kBHex)

        if let creds = loadCreds(), creds.expiresAt > Date().addingTimeInterval(45) {
            return try await SyncClient(creds: creds, kB: kB, transport: transport)
        }

        let fxa = FxAClient(transport: transport)
        let creds = try await fxa.syncCredentials(sessionToken: account.sessionTokenHex, kB: kB)
        // Signed out, or into another account, while the fetch ran: drop it.
        guard saveCreds(creds, ifGeneration: started) else { throw SyncError.notSignedIn }
        return try await SyncClient(creds: creds, kB: kB, transport: transport)
    }

    /// Runs `operation` with a connected client. When the storage server
    /// rejects the cached token-server credentials (401), they are dropped and
    /// the operation runs once more with fresh ones. A 401 rejects its own
    /// request before storing any record in it, so re-running one request is
    /// safe. The legacy multi-PUT paths (safe-sync off) are the exception: a
    /// 401 on a later PUT leaves the earlier ones applied, and the retry
    /// cannot tell that apart from a cold call.
    ///
    /// When a failure proves the stored account session is dead, the app is
    /// told via `.zenCompanionSessionInvalidated` so it can sign out and show
    /// the sign-in screen instead of a passive error line.
    static func withClient<T>(_ operation: (SyncClient) async throws -> T) async throws -> T {
        do {
            return try await operation(try await connect())
        } catch {
            if let sync = error as? SyncError, case .unauthorized = sync {
                invalidateCreds()
                do {
                    return try await operation(try await connect())
                } catch {
                    reportIfSessionInvalid(error)
                    throw error
                }
            }
            reportIfSessionInvalid(error)
            throw error
        }
    }

    private static func reportIfSessionInvalid(_ error: Error) {
        guard let sync = error as? SyncError, sync.isSessionInvalid else { return }
        os_log("AccountStore: account session rejected; signing out", log: .default, type: .error)
        // Sync work resumes off the main actor; deliver the event where the
        // session owner lives.
        DispatchQueue.main.async {
            NotificationCenter.default.post(name: .zenCompanionSessionInvalidated, object: nil)
        }
    }

    /// The current sign-in generation; pass it to `isCurrent` after async work.
    static var currentGeneration: Int {
        lock.withLock { generation }
    }

    /// False once the user signed out or switched accounts since `generation`.
    static func isCurrent(_ generation: Int) -> Bool {
        currentGeneration == generation
    }

    static func invalidateCreds() {
        lock.withLock { cachedCreds = nil }
    }

    // MARK: - Token credentials (memory only; short-lived by design)

    private static func loadCreds() -> TokenServerCreds? {
        lock.lock()
        defer { lock.unlock() }
        guard let creds = cachedCreds else { return nil }
        return creds.expiresAt > Date() ? creds : nil
    }

    private static func saveCreds(_ creds: TokenServerCreds, ifGeneration expected: Int) -> Bool {
        lock.withLock {
            guard generation == expected else { return false }
            cachedCreds = creds
            return true
        }
    }

    private static func storeInMemory(_ snapshot: AccountSnapshot) {
        lock.lock()
        cachedSnapshot = snapshot
        lock.unlock()
    }

    private static func scrubLegacyFiles() {
        fileStore.remove(sessionBackupURL)
        fileStore.remove(legacyFileURL)
        fileStore.remove(legacyCredsURL)
    }
}

// MARK: - Session events

extension Notification.Name {
    /// Posted by `AccountStore.withClient` when the server definitively
    /// rejected the stored Mozilla account session. The app signs out and
    /// lands on the sign-in screen with an explanation.
    static let zenCompanionSessionInvalidated = Notification.Name("zenCompanionSessionInvalidated")
}

// MARK: - Default stores

/// Keychain `AfterFirstUnlockThisDeviceOnly` generic password, shared with the
/// ShareExtension through the keychain-access-group.
final class KeychainSecureStore: AccountSecureStore {
    private static let service = "de.kjell.zencompanion.account"
    private static let accountKey = "fxa"

    private(set) var lastReadWasTransientFailure = false

    func read() -> Data? {
        lastReadWasTransientFailure = false
        var query = Self.keychainBase
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            return item as? Data
        case errSecItemNotFound:
            return nil
        default:
            lastReadWasTransientFailure = true
            return nil
        }
    }

    func write(_ data: Data) -> Bool {
        let attrs: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]

        let query = Self.keychainBase
        if SecItemUpdate(query as CFDictionary, attrs as CFDictionary) == errSecSuccess {
            return true
        }

        var newEntry = query
        newEntry[kSecValueData as String] = data
        newEntry[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(newEntry as CFDictionary, nil) == errSecSuccess
    }

    func delete() {
        SecItemDelete(Self.keychainBase as CFDictionary)
    }

    private static var keychainBase: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: accountKey
        ]
    }
}

struct DefaultAccountFileStore: AccountFileStore {
    func read(_ url: URL) -> Data? {
        try? Data(contentsOf: url)
    }

    func write(_ data: Data, to url: URL) throws {
        try data.write(to: url, options: .atomic)
    }

    func remove(_ url: URL) {
        try? FileManager.default.removeItem(at: url)
    }

    func exists(_ url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }
}
