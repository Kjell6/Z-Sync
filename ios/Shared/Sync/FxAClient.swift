import Foundation

/// Official Mozilla Accounts auth-server + Sync token-server protocol
/// (onepw + BrowserID), same path Firefox uses for Sync 1.5.
actor FxAClient {
    static let authBase = URL(string: "https://api.accounts.firefox.com/v1")!
    static let tokenServer = URL(string: "https://token.services.mozilla.com/1.0/sync/1.5")!
    static let userAgent = "Z-Sync/1.0 (iOS)"

    private let transport: SyncHTTPTransport

    init(transport: SyncHTTPTransport = URLSessionTransport.shared) {
        self.transport = transport
    }

    func completeWebLogin(email: String, uid: String, sessionToken: String, keyFetchToken: String, unwrapBKeyHex: String) async throws -> (uid: String, session: String, kB: Data) {
        let unwrap = try FxACrypto.unhex(unwrapBKeyHex)
        let kB = try await fetchKB(keyFetchToken: keyFetchToken, unwrapBKey: unwrap)
        return (uid, sessionToken, kB)
    }

    func syncCredentials(sessionToken: String, kB: Data) async throws -> TokenServerCreds {
        var last = "no token"
        do {
            let oauth = try await oauthAccessToken(sessionToken: sessionToken)
            let rotation = try await scopedKeyData(sessionToken: sessionToken)
            return try await tokenServerCreds(
                authorization: "Bearer \(oauth)",
                kB: kB,
                keysChangedAt: rotation
            )
        } catch {
            // D3 parity with Android: a TOTP challenge from the primary
            // OAuth/oldsync path must propagate instead of falling through to
            // the BrowserID flow, which would mask it as a plain auth failure.
            if let syncError = error as? SyncError, case .totpRequired = syncError {
                throw error
            }
            last = error.localizedDescription
        }
        do {
            let assertion = try await browserIDAssertion(
                sessionToken: sessionToken,
                audience: "https://token.services.mozilla.com"
            )
            return try await tokenServerCreds(authorization: "BrowserID \(assertion)", kB: kB, keysChangedAt: 0)
        } catch {
            throw SyncError.auth("Sync sign-in failed. \(last) / \(error.localizedDescription)")
        }
    }

    /// The oldsync scoped-key metadata, in particular the raw (millisecond)
    /// keyRotationTimestamp. Firefox puts this value verbatim into the token-server
    /// X-KeyID header for the legacy oldsync scope.
    private func scopedKeyData(sessionToken: String) async throws -> Int64 {
        let token = try FxACrypto.unhex(sessionToken)
        let url = URL(string: Self.authBase.absoluteString + "/account/scoped-key-data")!
        let json = try await hawkJSON(
            url: url,
            method: "POST",
            token: token,
            tokenType: "sessionToken",
            json: [
                "client_id": "5882386c6d801776",
                "scope": "https://identity.mozilla.com/apps/oldsync"
            ]
        )
        guard let meta = json["https://identity.mozilla.com/apps/oldsync"] as? [String: Any],
              let ts = (meta["keyRotationTimestamp"] as? NSNumber)?.int64Value
        else { throw SyncError.auth("missing keyRotationTimestamp") }
        return ts
    }

    /// Firefox-desktop client, fxa-credentials grant. OAuth wants Hawk, not a raw Bearer.
    private func oauthAccessToken(sessionToken: String) async throws -> String {
        let token = try FxACrypto.unhex(sessionToken)
        let body: [String: Any] = [
            "client_id": "5882386c6d801776",
            "grant_type": "fxa-credentials",
            "scope": "https://identity.mozilla.com/apps/oldsync",
            "access_type": "offline"
        ]
        let urls = [
            URL(string: "https://oauth.accounts.firefox.com/v1/token")!,
            URL(string: "https://api.accounts.firefox.com/v1/oauth/token")!
        ]
        var last = "OAuth failed"
        for url in urls {
            do {
                let obj = try await hawkJSON(url: url, method: "POST", token: token, tokenType: "sessionToken", json: body)
                if let access = obj["access_token"] as? String { return access }
                last = "OAuth response without access_token"
            } catch {
                last = error.localizedDescription
            }
        }
        throw SyncError.auth(last)
    }

    private func hawkJSON(
        url: URL,
        method: String,
        token: Data,
        tokenType: String,
        json: [String: Any]?
    ) async throws -> [String: Any] {
        let any = try await hawkAny(url: url, method: method, token: token, tokenType: tokenType, json: json)
        return any as? [String: Any] ?? [:]
    }

    private func hawkAny(
        url: URL,
        method: String,
        token: Data,
        tokenType: String,
        json: [String: Any]?
    ) async throws -> Any {
        let material = FxACrypto.tokenMaterial(token: token, type: tokenType)
        var headers: [String: String] = [
            "Content-Type": "application/json",
            "Accept": "application/json",
            "User-Agent": Self.userAgent
        ]
        var body: Data?
        if let json {
            body = try JSONSerialization.data(withJSONObject: json)
        }
        let hash = (body?.isEmpty == false) ? HawkAuth.payloadHash(body!) : nil
        headers["Authorization"] = HawkAuth.authorization(
            method: method,
            url: url,
            id: material.id,
            key: material.authKey,
            payloadHash: hash
        )
        let response = try await transport.send(
            SyncHTTPRequest(method: method, url: url, headers: headers, body: body)
        )
        let parsed = try? JSONSerialization.jsonObject(with: response.body)
        let obj = parsed as? [String: Any] ?? [:]
        guard (200..<300).contains(response.statusCode) else {
            // D3 parity: auth-server failures go through the same errno
            // mapping as every other path, so an errno 103 body surfaces as
            // `.totpRequired` here too instead of a plain auth error.
            if let error = Self.authError(status: response.statusCode, body: obj) {
                throw error
            }
            let text = (obj["message"] as? String) ?? (obj["error"] as? String) ?? String(data: response.body, encoding: .utf8) ?? "HTTP"
            throw SyncError.auth(text)
        }
        return parsed ?? [:]
    }

    private func tokenServerCreds(authorization: String, kB: Data, keysChangedAt: Int64) async throws -> TokenServerCreds {
        let kid = "\(keysChangedAt)-\(base64url(FxACrypto.clientStateBytes(kB: kB)))"
        let headers: [String: String] = [
            "Authorization": authorization,
            "X-KeyID": kid,
            "User-Agent": Self.userAgent
        ]
        let response = try await transport.send(
            SyncHTTPRequest(method: "GET", url: Self.tokenServer, headers: headers, body: nil)
        )
        guard (200..<300).contains(response.statusCode) else {
            let text = String(data: response.body, encoding: .utf8) ?? ""
            throw SyncError.auth("Token server: \(text)")
        }
        let data = response.body
        let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard let uid = obj?["uid"] as? String
                ?? (obj?["uid"] as? Int).map(String.init),
              let endpoint = obj?["api_endpoint"] as? String,
              let id = obj?["id"] as? String,
              let key = obj?["key"] as? String,
              let duration = obj?["duration"] as? Int
        else { throw SyncError.auth("Incomplete token server response.") }
        return TokenServerCreds(
            uid: uid,
            apiEndpoint: endpoint,
            hawkID: id,
            hawkKey: Data(key.utf8),
            expiresAt: Date().addingTimeInterval(TimeInterval(duration - 60))
        )
    }

    private func fetchKB(keyFetchToken: String, unwrapBKey: Data) async throws -> Data {
        let token = try FxACrypto.unhex(keyFetchToken)
        let material = FxACrypto.tokenMaterial(token: token, type: "keyFetchToken")
        // An unconfirmed account answers errno 104. While the user is still
        // waiting for the confirmation email, poll instead of failing —
        // the keys become fetchable the moment they confirm.
        var lastError: Error = SyncError.auth("keys missing")
        for attempt in 0..<40 {
            do {
                let json = try await getJSON(path: "/account/keys", bearer: "fxk_\(material.id)")
                guard let bundleHex = json["bundle"] as? String else { throw SyncError.auth("keys missing") }
                let bundle = try FxACrypto.unhex(bundleHex)
                let raw = try FxACrypto.unbundle(bundleKey: material.bundleKey, namespace: "account/keys", payload: bundle)
                guard raw.count >= 64 else { throw SyncError.crypto("keys short") }
                return try FxACrypto.xor(Data(raw.dropFirst(32).prefix(32)), unwrapBKey)
            } catch let e as SyncError {
                lastError = e
                guard case .auth(let message) = e, message.contains("Unconfirmed account") || message.contains("errno\":104") || message.contains("\"errno\":104") else { throw e }
                if attempt < 39 { try await Task.sleep(nanoseconds: 3_000_000_000) }
            }
        }
        throw lastError
    }

    private func browserIDAssertion(sessionToken: String, audience: String) async throws -> String {
        let pair = try RSAKeyPair.generate()
        let token = try FxACrypto.unhex(sessionToken)
        let json = try await hawkJSON(
            url: URL(string: Self.authBase.absoluteString + "/certificate/sign")!,
            method: "POST",
            token: token,
            tokenType: "sessionToken",
            json: [
                "publicKey": [
                    "algorithm": "RS",
                    "n": pair.modulusB64URL,
                    "e": pair.exponentB64URL
                ],
                "duration": 60_000
            ]
        )
        guard let cert = json["cert"] as? String else { throw SyncError.auth("missing certificate") }
        let assertion = try pair.signAssertion(audience: audience, expiresIn: 60)
        return cert + "~" + assertion
    }

    private func getJSON(path: String, bearer: String) async throws -> [String: Any] {
        let url = URL(string: Self.authBase.absoluteString + path)!
        let headers: [String: String] = [
            "Authorization": "Bearer \(bearer)",
            "Accept": "application/json",
            "User-Agent": Self.userAgent
        ]
        return try await send(SyncHTTPRequest(method: "GET", url: url, headers: headers, body: nil))
    }

    /// D3: single mapping from an auth-server failure to a `SyncError`, used
    /// by every Hawk/Bearer path so TOTP and plain auth failures are
    /// classified identically. Any status >= 400 is an auth failure; errno
    /// 103 means two-step authentication is required; everything else reports
    /// the server's message (falling back to the `error` field, then the
    /// status code).
    static func authError(status: Int, body: [String: Any]) -> SyncError? {
        guard status >= 400 else { return nil }
        if body["errno"] as? Int == 103 { return .totpRequired }
        let fallback = body["error"] as? String ?? "HTTP \(status)"
        return .auth((body["message"] as? String) ?? fallback)
    }

    private func send(_ request: SyncHTTPRequest) async throws -> [String: Any] {
        let response = try await transport.send(request)
        let obj = (try? JSONSerialization.jsonObject(with: response.body)) as? [String: Any] ?? [:]
        if let error = Self.authError(status: response.statusCode, body: obj) {
            throw error
        }
        return obj
    }
}

private struct RSAKeyPair {
    let privateKey: SecKey
    let modulusB64URL: String
    let exponentB64URL: String

    static func generate() throws -> RSAKeyPair {
        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeySizeInBits as String: 2048,
            kSecPrivateKeyAttrs as String: [kSecAttrIsPermanent: false]
        ]
        var error: Unmanaged<CFError>?
        guard let privateKey = SecKeyCreateRandomKey(attrs as CFDictionary, &error) else {
            throw SyncError.crypto("RSA generate")
        }
        guard let publicKey = SecKeyCopyPublicKey(privateKey),
              let data = SecKeyCopyExternalRepresentation(publicKey, &error) as Data?
        else { throw SyncError.crypto("RSA export") }
        let parsed = try parsePKCS1Public(data)
        return RSAKeyPair(privateKey: privateKey, modulusB64URL: parsed.n, exponentB64URL: parsed.e)
    }

    func signAssertion(audience: String, expiresIn: TimeInterval) throws -> String {
        let header = base64url(try JSONSerialization.data(withJSONObject: ["alg": "RS256"]))
        let now = Date()
        let payloadObj: [String: Any] = [
            "aud": audience,
            "exp": Int((now.addingTimeInterval(expiresIn).timeIntervalSince1970) * 1000)
        ]
        let payload = base64url(try JSONSerialization.data(withJSONObject: payloadObj))
        let signingInput = Data((header + "." + payload).utf8)
        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(
            privateKey,
            .rsaSignatureMessagePKCS1v15SHA256,
            signingInput as CFData,
            &error
        ) as Data?
        else { throw SyncError.crypto("RSA sign") }
        return header + "." + payload + "." + base64url(signature)
    }

    private static func parsePKCS1Public(_ data: Data) throws -> (n: String, e: String) {
        // SPKI or PKCS#1 — pull the two INTEGERs (n, e).
        var bytes = [UInt8](data)
        func skipLength(_ i: inout Int) throws -> Int {
            guard i < bytes.count else { throw SyncError.crypto("asn1") }
            var len = Int(bytes[i]); i += 1
            if len > 127 {
                let count = len & 0x7F
                len = 0
                for _ in 0..<count {
                    guard i < bytes.count else { throw SyncError.crypto("asn1") }
                    len = (len << 8) | Int(bytes[i]); i += 1
                }
            }
            return len
        }
        func nextInteger() throws -> Data {
            while true {
                guard let idx = bytes.firstIndex(of: 0x02) else { throw SyncError.crypto("no int") }
                var i = idx + 1
                let len = try skipLength(&i)
                let slice = Data(bytes[i..<(i + len)])
                bytes = Array(bytes[(i + len)...])
                var value = slice
                if value.first == 0 { value = value.dropFirst() }
                return value
            }
        }
        let n = try nextInteger()
        let e = try nextInteger()
        return (base64url(n), base64url(e))
    }
}

private func base64url(_ data: Data) -> String {
    data.base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
}
