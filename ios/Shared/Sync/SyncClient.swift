import Foundation
import os

/// Outcome of a conditionally-guarded BSO PUT (SPEC §7.2).
enum PutRecordOutcome: Equatable {
    /// 2xx: the record was written.
    case applied
    /// 412: the caller's condition is stale. `lastModified` is the target's
    /// current server timestamp from the response, when the server sent one.
    case preconditionFailed(lastModified: String?)
}

/// Outcome of an atomic collection POST (SPEC §7.2).
enum PostRecordsOutcome: Equatable {
    /// Every requested record is listed in the server's `success` array.
    case applied
    /// 412: the collection-level condition is stale.
    case preconditionFailed(lastModified: String?)
    /// 2xx but at least one requested id was rejected or missing from
    /// `success`.
    case partialFailure(succeeded: [String], failed: [String: String])
}

/// One cleartext record queued for an atomic `POST /storage/<collection>`.
struct SyncWriteRecord {
    let id: String
    let cleartext: [String: Any]
}

private extension SyncError {
    var isConflict: Bool {
        if case .conflict = self { return true }
        return false
    }
}

actor SyncClient {
    private let creds: TokenServerCreds
    private let defaultKeys: SyncCrypto.KeyBundle
    private let collectionKeys: [String: SyncCrypto.KeyBundle]
    private let transport: SyncHTTPTransport

    init(creds: TokenServerCreds, kB: Data, transport: SyncHTTPTransport = URLSessionTransport.shared) async throws {
        self.creds = creds
        self.transport = transport
        let syncKeys = SyncCrypto.syncKeyBundle(fromKB: kB)
        let loaded = try await Self.fetchCollectionKeys(creds: creds, syncKeys: syncKeys, transport: transport)
        self.defaultKeys = loaded.defaultBundle
        self.collectionKeys = loaded.perCollection
    }

    /// Test seam: skips the `crypto/keys` bootstrap fetch and installs known
    /// key bundles directly.
    init(
        creds: TokenServerCreds,
        defaultKeys: SyncCrypto.KeyBundle,
        collectionKeys: [String: SyncCrypto.KeyBundle],
        transport: SyncHTTPTransport = URLSessionTransport.shared
    ) {
        self.creds = creds
        self.transport = transport
        self.defaultKeys = defaultKeys
        self.collectionKeys = collectionKeys
    }

    private static func fetchCollectionKeys(
        creds: TokenServerCreds,
        syncKeys: SyncCrypto.KeyBundle,
        transport: SyncHTTPTransport
    ) async throws -> (defaultBundle: SyncCrypto.KeyBundle, perCollection: [String: SyncCrypto.KeyBundle]) {
        let record = try await requestJSON(creds: creds, method: "GET", path: "/storage/crypto/keys", allowMissing: true, transport: transport)
        if record["payload"] == nil {
            try await bootstrapCryptoKeys(creds: creds, syncKeys: syncKeys, transport: transport)
            return try await fetchCollectionKeys(creds: creds, syncKeys: syncKeys, transport: transport)
        }
        guard let payload = record["payload"] as? String else { throw SyncError.crypto("crypto/keys") }
        let plain = try SyncCrypto.decryptBSO(payloadJSON: payload, keys: syncKeys)
        let obj = try JSONSerialization.jsonObject(with: plain) as? [String: Any] ?? [:]
        func bundle(from array: [Any]?) throws -> SyncCrypto.KeyBundle {
            guard let encB64 = array?[0] as? String,
                  let hmacB64 = array?[1] as? String,
                  let enc = Data(base64Encoded: encB64),
                  let hmac = Data(base64Encoded: hmacB64)
            else { throw SyncError.crypto("collection key") }
            return SyncCrypto.KeyBundle(encryptionKey: enc, hmacKey: hmac)
        }
        let def = try bundle(from: obj["default"] as? [Any])
        var map: [String: SyncCrypto.KeyBundle] = [:]
        if let cols = obj["collections"] as? [String: [Any]] {
            for (name, arr) in cols {
                map[name] = try bundle(from: arr)
            }
        }
        return (def, map)
    }

    /// First write on an empty Sync node: create the default collection keys.
    /// The PUT is conditioned on timestamp `"0"` (SPEC §7.2 create-if-absent):
    /// if a concurrent device created the record first the server answers 412
    /// and we do not overwrite — the caller's re-read then decrypts the
    /// winner's payload.
    private static func bootstrapCryptoKeys(creds: TokenServerCreds, syncKeys: SyncCrypto.KeyBundle, transport: SyncHTTPTransport) async throws {
        let enc = try SyncCrypto.secureRandomBytes(32)
        let hmac = try SyncCrypto.secureRandomBytes(32)
        let object: [String: Any] = [
            "default": [enc.base64EncodedString(), hmac.base64EncodedString()],
            "collections": [:] as [String: Any]
        ]
        let body = try JSONSerialization.data(withJSONObject: object)
        let payload = try SyncCrypto.encryptBSO(plaintext: body, keys: syncKeys)
        _ = try await requestRaw(
            creds: creds,
            method: "PUT",
            path: "/storage/crypto/keys",
            json: ["payload": payload],
            allowMissing: false,
            extraHeaders: ["X-If-Unmodified-Since": "0"],
            toleratePreconditionFailure: true,
            transport: transport
        )
    }

    func keys(for collection: String) -> SyncCrypto.KeyBundle {
        collectionKeys[collection] ?? defaultKeys
    }

    func infoCollections() async throws -> [String: Double] {
        let obj = try await requestJSON(method: "GET", path: "/info/collections")
        var out: [String: Double] = [:]
        for (k, v) in obj {
            if let n = v as? Double { out[k] = n }
            else if let n = v as? Int { out[k] = Double(n) }
        }
        return out
    }

    /// Follows the server's pagination (`X-Weave-Next-Offset`) so accounts
    /// with more records than one page returns still get everything.
    func getRecords(collection: String) async throws -> [[String: Any]] {
        let (records, _) = try await Self.readCollection(
            creds: creds,
            collection: collection,
            transport: transport,
            conditional: false
        )
        return records
    }

    /// Consistent collection read for conflict-safe writes (SPEC §7.2):
    /// captures the collection's `X-Last-Modified` and, while paginating,
    /// re-sends it as `X-If-Unmodified-Since` so later pages cannot mix two
    /// server states. A 412 mid-read restarts the whole read once; a second
    /// failure throws `SyncError.conflict`.
    func getCollectionWithMetadata(
        collection: String
    ) async throws -> (records: [[String: Any]], lastModified: String?) {
        for attempt in 0..<2 {
            do {
                return try await Self.readCollection(
                    creds: creds,
                    collection: collection,
                    transport: transport,
                    conditional: true
                )
            } catch let error as SyncError where error.isConflict {
                if attempt == 1 { throw SyncError.conflict }
            }
        }
        throw SyncError.conflict
    }

    private static func readCollection(
        creds: TokenServerCreds,
        collection: String,
        transport: SyncHTTPTransport,
        conditional: Bool
    ) async throws -> (records: [[String: Any]], lastModified: String?) {
        var records: [[String: Any]] = []
        var offset: String?
        var pages = 0
        var lastModified: String?
        while pages < 50 {
            var path = "/storage/\(collection)?full=1&limit=2500"
            if let offset, !offset.isEmpty,
               let encoded = offset.addingPercentEncoding(withAllowedCharacters: Self.unreservedURLCharacters) {
                path += "&offset=\(encoded)"
            }
            // Page one has no timestamp to condition on; later pages are
            // pinned to the state captured from page one.
            var headers: [String: String] = [:]
            if conditional, let lastModified {
                headers["X-If-Unmodified-Since"] = lastModified
            }
            let (data, http) = try await Self.requestRaw(
                creds: creds,
                method: "GET",
                path: path,
                allowMissing: true,
                extraHeaders: headers,
                toleratePreconditionFailure: conditional,
                transport: transport
            )
            if conditional, http.statusCode == 412 {
                throw SyncError.conflict
            }
            if lastModified == nil, (200..<300).contains(http.statusCode) {
                lastModified = http.header("X-Last-Modified")
            }
            pages += 1
            guard http.statusCode != 404, !data.isEmpty else { break }
            let page = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] ?? []
            records += page
            // Stop unless the server both signals a next page AND delivered
            // content this round; an unchanged token means it is stuck.
            let nextOffset = http.header("X-Weave-Next-Offset")
            guard let nextOffset, !nextOffset.isEmpty, !page.isEmpty, nextOffset != offset else { break }
            offset = nextOffset
        }
        Self.log.debug("getRecords \(collection, privacy: .public): \(pages, privacy: .public) pages, \(records.count, privacy: .public) records")
        return (records, lastModified)
    }

    private static let log = Logger(subsystem: "de.kjell.zencompanion", category: "sync-client")

    private static let unreservedURLCharacters = CharacterSet(
        charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
    )

    /// One GET page of a collection, plus the server's next-offset token.
    private static func requestPage(
        creds: TokenServerCreds,
        path: String,
        transport: SyncHTTPTransport
    ) async throws -> (page: [[String: Any]], nextOffset: String?) {
        let (data, http) = try await requestRaw(creds: creds, method: "GET", path: path, transport: transport)
        guard http.statusCode != 404, !data.isEmpty else { return ([], nil) }
        let list = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] ?? []
        return (list, http.header("X-Weave-Next-Offset"))
    }

    /// The newest page of a collection, decrypted. Large read-only
    /// collections (history) don't need full pagination — the server's
    /// `sort=newest` orders by last-modified, so the first page holds the
    /// most recently changed records.
    func getRecentRecords(collection: String, limit: Int) async throws -> [[String: Any]] {
        let path = "/storage/\(collection)?full=1&limit=\(limit)&sort=newest"
        let (page, _) = try await Self.requestPage(creds: creds, path: path, transport: transport)
        return page
    }

    func decryptRecord(collection: String, record: [String: Any]) throws -> [String: Any] {
        guard let payload = record["payload"] as? String else { return [:] }
        let data = try SyncCrypto.decryptBSO(payloadJSON: payload, keys: keys(for: collection))
        return (try JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    func putRecord(collection: String, id: String, object: [String: Any]) async throws {
        _ = try await putRecord(
            collection: collection,
            id: id,
            object: object,
            ifUnmodifiedSince: nil
        )
    }

    /// Conditional variant of `putRecord` (SPEC §7.2). With
    /// `ifUnmodifiedSince == nil` this is byte-identical to the legacy
    /// unconditional write, including throwing on non-2xx. When a condition
    /// is set, a 412 is reported as `.preconditionFailed` (with the server's
    /// current timestamp when present) instead of throwing.
    @discardableResult
    func putRecord(
        collection: String,
        id: String,
        object: [String: Any],
        ifUnmodifiedSince: String?
    ) async throws -> PutRecordOutcome {
        let body = try JSONSerialization.data(withJSONObject: object)
        let payload = try SyncCrypto.encryptBSO(plaintext: body, keys: keys(for: collection))
        let bso: [String: Any] = ["payload": payload]
        let conditional = ifUnmodifiedSince.map { ["X-If-Unmodified-Since": $0] } ?? [:]
        let (_, http) = try await Self.requestRaw(
            creds: creds,
            method: "PUT",
            path: "/storage/\(collection)/\(Self.encodedBSOId(id))",
            json: bso,
            allowMissing: false,
            extraHeaders: conditional,
            toleratePreconditionFailure: ifUnmodifiedSince != nil,
            transport: transport
        )
        if ifUnmodifiedSince != nil, http.statusCode == 412 {
            return .preconditionFailed(lastModified: http.header("X-Last-Modified"))
        }
        return .applied
    }

    /// Atomic multi-record write (SPEC §7.2): `POST /storage/<collection>`
    /// with a JSON array of `{id, payload}` BSOs. With `ifUnmodifiedSince`
    /// set the whole batch is conditioned on the collection timestamp; a 412
    /// is reported as `.preconditionFailed`. Any requested id the server
    /// does not list in `success` counts as a failure.
    @discardableResult
    func postRecords(
        collection: String,
        records: [SyncWriteRecord],
        ifUnmodifiedSince: String?
    ) async throws -> PostRecordsOutcome {
        var bsoList: [[String: Any]] = []
        for record in records {
            let body = try JSONSerialization.data(withJSONObject: record.cleartext)
            let payload = try SyncCrypto.encryptBSO(plaintext: body, keys: keys(for: collection))
            bsoList.append(["id": record.id, "payload": payload])
        }
        let conditional = ifUnmodifiedSince.map { ["X-If-Unmodified-Since": $0] } ?? [:]
        let (data, http) = try await Self.requestRaw(
            creds: creds,
            method: "POST",
            path: "/storage/\(collection)",
            jsonArray: bsoList,
            allowMissing: false,
            extraHeaders: conditional,
            toleratePreconditionFailure: ifUnmodifiedSince != nil,
            transport: transport
        )
        if ifUnmodifiedSince != nil, http.statusCode == 412 {
            return .preconditionFailed(lastModified: http.header("X-Last-Modified"))
        }

        let outcome = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let success = Set(outcome["success"] as? [String] ?? [])
        var failed: [String: String] = [:]
        if let raw = outcome["failed"] as? [String: Any] {
            for (id, reason) in raw {
                failed[id] = reason as? String ?? String(describing: reason)
            }
        }
        let requested = records.map(\.id)
        for id in requested where !success.contains(id) && failed[id] == nil {
            failed[id] = "not in success"
        }
        guard failed.isEmpty else {
            return .partialFailure(
                succeeded: requested.filter { success.contains($0) },
                failed: failed
            )
        }
        return .applied
    }

    /// Weave tombstone: encrypted `{ id, deleted: true }` payload.
    /// Zen's Spaces engine (`CryptoWrapper.deleted` lives on *cleartext*)
    /// decrypts incoming records before routing tombstones; a raw WBO
    /// `{deleted:true}` with no ciphertext never applies and the desktop
    /// re-uploads the pin on the next sync.
    func putTombstone(collection: String, id: String) async throws {
        try await putRecord(
            collection: collection,
            id: id,
            object: ["id": id, "deleted": true]
        )
    }

    /// BSO ids are opaque server-side strings; Zen's spaces engine uses
    /// braced UUIDs (`{…}`). Braces are not valid raw in a URL and URLSession
    /// would re-encode them, so percent-encode every id to the exact form
    /// both the wire and the Hawk signature use.
    static func encodedBSOId(_ id: String) -> String {
        id.addingPercentEncoding(withAllowedCharacters: unreservedURLCharacters) ?? id
    }

    private func requestJSON(
        method: String,
        path: String,
        json: [String: Any]? = nil,
        allowMissing: Bool = true
    ) async throws -> [String: Any] {
        let any = try await requestAny(method: method, path: path, json: json, allowMissing: allowMissing)
        return any as? [String: Any] ?? [:]
    }

    private static func requestJSON(
        creds: TokenServerCreds,
        method: String,
        path: String,
        json: [String: Any]? = nil,
        allowMissing: Bool = false,
        transport: SyncHTTPTransport
    ) async throws -> [String: Any] {
        let any = try await requestAny(creds: creds, method: method, path: path, json: json, allowMissing: allowMissing, transport: transport)
        return any as? [String: Any] ?? [:]
    }

    private func requestAny(
        method: String,
        path: String,
        json: [String: Any]? = nil,
        allowMissing: Bool = true
    ) async throws -> Any {
        try await Self.requestAny(creds: creds, method: method, path: path, json: json, allowMissing: allowMissing, transport: transport)
    }

    private static func requestRaw(
        creds: TokenServerCreds,
        method: String,
        path: String,
        json: [String: Any]? = nil,
        jsonArray: [[String: Any]]? = nil,
        allowMissing: Bool = true,
        extraHeaders: [String: String] = [:],
        toleratePreconditionFailure: Bool = false,
        transport: SyncHTTPTransport
    ) async throws -> (data: Data, http: SyncHTTPResponse) {
        let fullURLString = creds.apiEndpoint.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + path
        guard let url = URL(string: fullURLString) else {
            throw SyncError.network("bad sync url")
        }
        // Sync credentials must never leave the device over plaintext HTTP;
        // enforced before signing or sending (SPEC §7).
        guard url.scheme?.lowercased() == "https" else {
            throw SyncError.network("insecure sync endpoint")
        }
        // Hawk must sign the exact request-line resource (path + query) with
        // percent-encoding preserved. `URL.path` decodes escapes, so derive the
        // resource from the verbatim URL string instead.
        var resource = fullURLString
        if let schemeRange = resource.range(of: "://") {
            let afterScheme = resource[schemeRange.upperBound...]
            if let slash = afterScheme.firstIndex(of: "/") {
                resource = String(afterScheme[slash...])
            } else {
                resource = "/"
            }
        }
        var headers: [String: String] = ["User-Agent": FxAClient.userAgent]
        headers.merge(extraHeaders) { _, new in new }
        var body: Data?
        if let json {
            let encoded = try JSONSerialization.data(withJSONObject: json)
            body = encoded
            headers["Content-Type"] = "application/json"
        } else if let jsonArray {
            let encoded = try JSONSerialization.data(withJSONObject: jsonArray)
            body = encoded
            headers["Content-Type"] = "application/json"
        }
        let hash = (body?.isEmpty == false) ? HawkAuth.payloadHash(body!) : nil
        headers["Authorization"] = HawkAuth.authorization(
            method: method,
            url: url,
            id: creds.hawkID,
            key: creds.hawkKey,
            payloadHash: hash,
            resource: resource
        )
        let http = try await transport.send(
            SyncHTTPRequest(method: method, url: url, headers: headers, body: body)
        )
        // Conditional callers turn 412 into an outcome instead of an error;
        // the response's X-Last-Modified carries the fresh timestamp.
        if toleratePreconditionFailure, http.statusCode == 412 {
            return (http.body, http)
        }
        if http.statusCode == 404, allowMissing { return (Data(), http) }
        guard (200..<300).contains(http.statusCode) else {
            let text = String(data: http.body, encoding: .utf8) ?? ""
            var detail = "HTTP \(http.statusCode) \(method) \(path): \(text)"
            // Hawk auth failures carry the server's verdict here
            // (e.g. ts-skew via the Timestamp header).
            for header in ["WWW-Authenticate", "X-Timestamp"] {
                if let value = http.header(header) {
                    detail += " | \(header): \(value)"
                }
            }
            throw SyncError.network(detail)
        }
        return (http.body, http)
    }

    private static func requestAny(
        creds: TokenServerCreds,
        method: String,
        path: String,
        json: [String: Any]? = nil,
        allowMissing: Bool = true,
        transport: SyncHTTPTransport
    ) async throws -> Any {
        let (data, _) = try await requestRaw(creds: creds, method: method, path: path, json: json, allowMissing: allowMissing, transport: transport)
        if data.isEmpty { return [:] as [String: Any] }
        // PUT/DELETE return the new collection timestamp as a bare number, not JSON.
        if let text = String(data: data, encoding: .utf8) {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty, let first = trimmed.first, first.isNumber || first == "-" {
                return text
            }
        }
        return try JSONSerialization.jsonObject(with: data)
    }
}
