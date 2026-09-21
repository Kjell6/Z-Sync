import CryptoKit
import Foundation

enum HawkAuth {
    static func authorization(
        method: String,
        url: URL,
        id: String,
        key: Data,
        extraHeaders: [String: String] = [:],
        payloadHash: String? = nil,
        resource: String? = nil,
        fixedTimestamp: Int? = nil,
        fixedNonce: String? = nil
    ) -> String {
        let ts = fixedTimestamp ?? Int(Date().timeIntervalSince1970)
        let nonce = fixedNonce ?? randomNonce()
        let host = url.host ?? ""
        let port = url.port ?? (url.scheme == "https" ? 443 : 80)
        // `URL.path` decodes percent-escapes, but Hawk must sign the exact
        // encoded resource the request line carries. Callers that build paths
        // with percent-encoded segments pass `resource` explicitly.
        var path: String
        if let resource {
            path = resource.hasPrefix("/") ? resource : "/" + resource
        } else {
            path = url.path.isEmpty ? "/" : url.path
            if let query = url.query, !query.isEmpty {
                path += "?" + query
            }
        }
        let hash = payloadHash ?? ""
        let ext = ""
        let normalized = [
            "hawk.1.header",
            "\(ts)",
            nonce,
            method.uppercased(),
            path,
            host.lowercased(),
            "\(port)",
            hash,
            ext,
            ""
        ].joined(separator: "\n")
        let macB64 = macFor(normalized: normalized, key: key)
        var parts = [
            "id=\"\(id)\"",
            "ts=\"\(ts)\"",
            "nonce=\"\(nonce)\"",
            "mac=\"\(macB64)\""
        ]
        if !hash.isEmpty {
            parts.append("hash=\"\(hash)\"")
        }
        _ = extraHeaders
        return "Hawk " + parts.joined(separator: ", ")
    }

    static func macFor(normalized: String, key: Data) -> String {
        FxACrypto.hmacSHA256(key: key, data: Data(normalized.utf8)).base64EncodedString()
    }

    static func payloadHash(_ body: Data, contentType: String = "application/json") -> String {
        let normalizedType = contentType.split(separator: ";").first.map(String.init) ?? contentType
        var data = Data("hawk.1.payload\n\(normalizedType)\n".utf8)
        data.append(body)
        data.append(0x0A)
        return Data(SHA256.hash(data: data)).base64EncodedString()
    }

    private static func randomNonce() -> String {
        var bytes = [UInt8](repeating: 0, count: 8)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "")
            .replacingOccurrences(of: "/", with: "")
            .replacingOccurrences(of: "=", with: "")
    }
}
