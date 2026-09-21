import Foundation

struct AccountSnapshot: Codable {
    var email: String
    var uid: String
    var sessionTokenHex: String
    var kBHex: String
    /// Bundled sample data for App Review. Never sent to Mozilla.
    var isDemo: Bool

    init(
        email: String,
        uid: String,
        sessionTokenHex: String,
        kBHex: String,
        isDemo: Bool = false
    ) {
        self.email = email
        self.uid = uid
        self.sessionTokenHex = sessionTokenHex
        self.kBHex = kBHex
        self.isDemo = isDemo
    }

    private enum CodingKeys: String, CodingKey {
        case email, uid, sessionTokenHex, kBHex, isDemo
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        email = try c.decode(String.self, forKey: .email)
        uid = try c.decode(String.self, forKey: .uid)
        sessionTokenHex = try c.decode(String.self, forKey: .sessionTokenHex)
        kBHex = try c.decode(String.self, forKey: .kBHex)
        isDemo = try c.decodeIfPresent(Bool.self, forKey: .isDemo) ?? false
    }
}

struct TokenServerCreds: Codable {
    var uid: String
    var apiEndpoint: String
    var hawkID: String
    var hawkKey: Data
    var expiresAt: Date
}

// `SearchEngine` lives in `Shared/Data/SearchEngine.swift`.
