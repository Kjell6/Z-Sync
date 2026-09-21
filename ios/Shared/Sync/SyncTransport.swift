import Foundation

/// A minimal, platform-neutral Sync HTTP exchange used by `SyncClient` and
/// `FxAClient`. The production implementation is `URLSessionTransport`; tests
/// inject a fake so no hermetic test touches the network.
struct SyncHTTPRequest {
    let method: String
    let url: URL
    let headers: [String: String]
    let body: Data?
}

struct SyncHTTPResponse {
    let statusCode: Int
    /// Header names are lowercased so lookups are case-insensitive on every
    /// platform.
    let headers: [String: String]
    let body: Data

    func header(_ name: String) -> String? {
        headers[name.lowercased()]
    }
}

protocol SyncHTTPTransport: AnyObject {
    func send(_ request: SyncHTTPRequest) async throws -> SyncHTTPResponse
}

/// Production transport. Request bytes are identical to the previous direct
/// `URLSession.shared.data(for:)` calls: same method, URL, headers, and body.
/// Redirects are never followed: a 3xx surfaces to the caller as-is, so sync
/// credentials cannot be bounced to another host.
final class URLSessionTransport: SyncHTTPTransport {
    static let shared = URLSessionTransport()

    private let session: URLSession

    private final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate {
        func urlSession(
            _ session: URLSession,
            task: URLSessionTask,
            willPerformHTTPRedirection response: HTTPURLResponse,
            newRequest request: URLRequest,
            completionHandler: @escaping (URLRequest?) -> Void
        ) {
            // nil cancels the redirect; URLSession then reports the original
            // 3xx response as the task result (fail closed).
            completionHandler(nil)
        }
    }

    private static func makeSession() -> URLSession {
        URLSession(configuration: URLSessionConfiguration.default, delegate: NoRedirectDelegate(), delegateQueue: nil)
    }

    init(session: URLSession = URLSessionTransport.makeSession()) {
        self.session = session
    }

    func send(_ request: SyncHTTPRequest) async throws -> SyncHTTPResponse {
        var urlRequest = URLRequest(url: request.url)
        urlRequest.httpMethod = request.method
        for (name, value) in request.headers {
            urlRequest.setValue(value, forHTTPHeaderField: name)
        }
        urlRequest.httpBody = request.body

        let (data, response) = try await session.data(for: urlRequest)
        guard let http = response as? HTTPURLResponse else {
            throw SyncError.network("no response")
        }
        var headers: [String: String] = [:]
        for (key, value) in http.allHeaderFields {
            guard let key = key as? String else { continue }
            headers[key.lowercased()] = value as? String ?? String(describing: value)
        }
        return SyncHTTPResponse(statusCode: http.statusCode, headers: headers, body: data)
    }
}
