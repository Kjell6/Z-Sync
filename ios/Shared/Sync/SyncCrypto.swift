import CommonCrypto
import Foundation

enum SyncCrypto {
    struct KeyBundle: Codable {
        var encryptionKey: Data
        var hmacKey: Data
    }

    static func syncKeyBundle(fromKB kB: Data) -> KeyBundle {
        let info = Data("identity.mozilla.com/picl/v1/oldsync".utf8)
        let material = FxACrypto.hkdf(secret: kB, info: info, length: 64)
        return KeyBundle(
            encryptionKey: Data(material.prefix(32)),
            hmacKey: Data(material.suffix(32))
        )
    }

    static func decryptBSO(payloadJSON: String, keys: KeyBundle) throws -> Data {
        struct Envelope: Decodable {
            let ciphertext: String
            let IV: String
            let hmac: String
        }
        let env = try JSONDecoder().decode(Envelope.self, from: Data(payloadJSON.utf8))
        let actual = FxACrypto.hmacSHA256(key: keys.hmacKey, data: Data(env.ciphertext.utf8))
        // Case-insensitive hex decode preserved from the string-compare era;
        // an unparseable hmac counts as a mismatch.
        let expected = (try? FxACrypto.unhex(env.hmac.lowercased())) ?? Data()
        guard FxACrypto.constantTimeEquals(actual, expected) else { throw SyncError.crypto("bso hmac mismatch") }
        guard let ciphertext = Data(base64Encoded: env.ciphertext),
              let iv = Data(base64Encoded: env.IV)
        else { throw SyncError.crypto("bso base64") }
        // Envelope geometry (SPEC §4): validated after the HMAC so a forged
        // envelope cannot drive decoder buffer sizing.
        guard iv.count == 16 else { throw SyncError.crypto("bso iv length") }
        guard !ciphertext.isEmpty && ciphertext.count % 16 == 0 else { throw SyncError.crypto("bso ciphertext length") }
        return try aes256CBC(decrypt: ciphertext, key: keys.encryptionKey, iv: iv)
    }

    /// Security-framework RNG. A failed copy is a hard error — an all-zero
    /// IV or key from an ignored status would silently break the contract.
    static func secureRandomBytes(_ count: Int) throws -> Data {
        guard count > 0 else { return Data() }
        var bytes = Data(count: count)
        let status = bytes.withUnsafeMutableBytes { buffer in
            SecRandomCopyBytes(kSecRandomDefault, count, buffer.baseAddress!)
        }
        guard status == errSecSuccess else { throw SyncError.crypto("rng failure") }
        return bytes
    }

    static func encryptBSO(plaintext: Data, keys: KeyBundle) throws -> String {
        let iv = try secureRandomBytes(16)
        let cipher = try aes256CBC(encrypt: plaintext, key: keys.encryptionKey, iv: iv)
        let ciphertextB64 = cipher.base64EncodedString()
        let hmac = FxACrypto.hex(FxACrypto.hmacSHA256(key: keys.hmacKey, data: Data(ciphertextB64.utf8)))
        let obj: [String: String] = [
            "ciphertext": ciphertextB64,
            "IV": iv.base64EncodedString(),
            "hmac": hmac
        ]
        return String(data: try JSONSerialization.data(withJSONObject: obj), encoding: .utf8) ?? "{}"
    }

    private static func aes256CBC(encrypt data: Data, key: Data, iv: Data) throws -> Data {
        try crypt(data, key: key, iv: iv, operation: CCOperation(kCCEncrypt))
    }

    private static func aes256CBC(decrypt data: Data, key: Data, iv: Data) throws -> Data {
        try crypt(data, key: key, iv: iv, operation: CCOperation(kCCDecrypt))
    }

    private static func crypt(_ data: Data, key: Data, iv: Data, operation: CCOperation) throws -> Data {
        var out = Data(count: data.count + kCCBlockSizeAES128)
        var moved = 0
        let outCount = out.count
        let status = out.withUnsafeMutableBytes { outBytes in
            data.withUnsafeBytes { inBytes in
                key.withUnsafeBytes { keyBytes in
                    iv.withUnsafeBytes { ivBytes in
                        CCCrypt(
                            operation,
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
