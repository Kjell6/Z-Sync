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
        guard let account = load(), !account.isDemo else { throw SyncError.notSignedIn }
        let kB = try FxACrypto.unhex(account.kBHex)

        if let creds = loadCreds(), creds.expiresAt > Date().addingTimeInterval(45) {
            return try await SyncClient(creds: creds, kB: kB, transport: transport)
        }

        let fxa = FxAClient(transport: transport)
        let creds = try await fxa.syncCredentials(sessionToken: account.sessionTokenHex, kB: kB)
        saveCreds(creds)
        return try await SyncClient(creds: creds, kB: kB, transport: transport)
    }

    // MARK: - Token credentials (memory only; short-lived by design)

    private static func loadCreds() -> TokenServerCreds? {
        lock.lock()
        defer { lock.unlock() }
        guard let creds = cachedCreds else { return nil }
        return creds.expiresAt > Date() ? creds : nil
    }

    private static func saveCreds(_ creds: TokenServerCreds) {
        lock.lock()
        cachedCreds = creds
        lock.unlock()
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
