import CryptoKit
import Foundation

enum FxACrypto {
    static let namespace = "identity.mozilla.com/picl/v1/"

    static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    static func unhex(_ string: String) throws -> Data {
        let raw = string.trimmingCharacters(in: .whitespacesAndNewlines)
        guard raw.count.isMultiple(of: 2) else { throw SyncError.crypto("invalid hex") }
        var data = Data()
        data.reserveCapacity(raw.count / 2)
        var index = raw.startIndex
        while index < raw.endIndex {
            let next = raw.index(index, offsetBy: 2)
            guard let byte = UInt8(raw[index..<next], radix: 16) else {
                throw SyncError.crypto("invalid hex")
            }
            data.append(byte)
            index = next
        }
        return data
    }

    /// HKDF-SHA256. The contract (shared/contract/SPEC.md §4) pins empty salt
    /// for all PICL derivations, which RFC 5869 §2.2 expands to `HashLen`
    /// zero bytes; the explicit parameter exists so the RFC 5869 known-answer
    /// fixture (`crypto-hkdf-rfc5869-case1`) can drive a non-empty salt too.
    static func hkdf(secret: Data, salt: Data = Data(), info: Data, length: Int) -> Data {
        let derived = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: secret),
            salt: salt,
            info: info,
            outputByteCount: length
        )
        return derived.withUnsafeBytes { Data($0) }
    }

    static func namespacedHKDF(secret: Data, name: String, length: Int) -> Data {
        hkdf(secret: secret, info: Data((namespace + name).utf8), length: length)
    }

    static func xor(_ a: Data, _ b: Data) throws -> Data {
        guard a.count == b.count else { throw SyncError.crypto("xor length mismatch") }
        return Data(zip(a, b).map { $0 ^ $1 })
    }

    static func hmacSHA256(key: Data, data: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: key)))
    }

    /// Constant-time comparison: XOR-accumulates every byte of the longer
    /// input with no early exit, so MAC verification never leaks through
    /// timing. True only when the lengths match AND the accumulated
    /// difference is zero.
    static func constantTimeEquals(_ a: Data, _ b: Data) -> Bool {
        var diff: UInt8 = 0
        for index in 0..<max(a.count, b.count) {
            let left = index < a.count ? a[a.startIndex + index] : 0
            let right = index < b.count ? b[b.startIndex + index] : 0
            diff |= left ^ right
        }
        return a.count == b.count && diff == 0
    }

    static func unbundle(bundleKey: Data, namespace name: String, payload: Data) throws -> Data {
        guard payload.count >= 32 else { throw SyncError.crypto("bundle too short") }
        let ciphertext = payload.dropLast(32)
        let expected = payload.suffix(32)
        let material = namespacedHKDF(secret: bundleKey, name: name, length: 32 + ciphertext.count)
        let hmacKey = material.prefix(32)
        let xorKey = material.dropFirst(32)
        let actual = hmacSHA256(key: Data(hmacKey), data: Data(ciphertext))
        guard constantTimeEquals(actual, expected) else { throw SyncError.crypto("bundle hmac mismatch") }
        return try xor(Data(ciphertext), Data(xorKey))
    }

    static func clientStateBytes(kB: Data) -> Data {
        Data(SHA256.hash(data: kB).prefix(16))
    }

    static func tokenMaterial(token: Data, type: String) -> (id: String, authKey: Data, bundleKey: Data) {
        let material = namespacedHKDF(secret: token, name: type, length: 96)
        return (
            hex(material.prefix(32)),
            Data(material.dropFirst(32).prefix(32)),
            Data(material.dropFirst(64).prefix(32))
        )
    }
}

enum SyncError: LocalizedError {
    case crypto(String)
    /// A Mozilla account failure. `sessionInvalid` is set only when the
    /// server definitively rejected the stored session (the token itself was
    /// refused), so signing in again is the only fix. Network failures,
    /// HTTP 5xx and rate limits stay `false`.
    case auth(String, sessionInvalid: Bool = false)
    case network(String)
    case notSignedIn
    case totpRequired
    case storageUnavailable
    /// Conditional write conflict that survived the single retry mandated by
    /// SPEC §7.2, or a partial batch write.
    case conflict
    /// The space a write targets is absent or deleted on the server (SPEC §7.5).
    case targetMissing
    /// The planning read was truncated, malformed or undecryptable, so the
    /// write was refused before anything changed (SPEC §7.5).
    case incompleteRead
    /// `meta/global` reports a Spaces engine or storage version this app
    /// does not know (SPEC §7.5).
    case unsupportedSyncVersion
    /// The storage server rejected the token-server credentials (HTTP 401).
    /// `AccountStore.withClient` drops them and retries once. `sessionInvalid`
    /// is false for device-environment rejections (clock skew, stale
    /// generation) that a new sign-in would not fix.
    case unauthorized(sessionInvalid: Bool = true)

    var errorDescription: String? {
        switch self {
        case .crypto(let m), .auth(let m, _), .network(let m): m
        case .notSignedIn: String(localized: "error.not_signed_in")
        case .totpRequired: String(localized: "error.totp")
        case .storageUnavailable: String(localized: "error.storage_unavailable")
        case .conflict: String(localized: "error.conflict")
        case .targetMissing: String(localized: "error.target_missing")
        case .incompleteRead: String(localized: "error.incomplete_read")
        case .unsupportedSyncVersion: String(localized: "error.unsupported_sync_version")
        case .unauthorized: String(localized: "error.auth")
        }
    }

    /// True only when the server definitively rejected the stored Mozilla
    /// account session, so the app must sign out and ask for a new sign-in.
    /// Temporary conditions (offline, timeouts, HTTP 5xx, rate limits) and
    /// recoverable states (TOTP, unverified account) stay `false`.
    var isSessionInvalid: Bool {
        switch self {
        case .auth(_, let sessionInvalid): sessionInvalid
        // `withClient` retries a storage 401 once with fresh credentials, so
        // an `unauthorized` that escapes means the fresh credentials were
        // rejected too. The flag keeps clock-skew/generation rejections out.
        case .unauthorized(let sessionInvalid): sessionInvalid
        default: false
        }
    }
}
