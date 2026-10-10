import Foundation

/// Reads the start of an http(s) document and returns its browser tab title.
/// The share sheet has no document, so this is how a shared link gets the
/// name a browser would show. Only the first 64KB is kept: `<title>` is in `<head>`.
enum PageTitleLoader {
    static func fetchTitle(for url: URL) async -> String? {
        guard ShareLink.httpURL(from: url) != nil else { return nil }
        guard let fetched = await PrefixFetcher().fetch(url) else { return nil }
        let html = PageTitle.htmlString(from: fetched.data, contentType: fetched.contentType)
        guard let title = PageTitle.extract(fromHTML: html, url: url) else { return nil }
        if let finalURL = fetched.finalURL, PageTitle.usable(title, url: finalURL) == nil {
            return nil
        }
        return title
    }
}

private final class PrefixFetcher: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Payload?, Never>?
    private var data = Data()
    private var contentType: String?
    private var finalURL: URL?
    private var accepted = false
    private let limit = 65_536

    struct Payload {
        var data: Data
        var contentType: String?
        var finalURL: URL?
    }

    func fetch(_ url: URL) async -> Payload? {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 4
        config.timeoutIntervalForResource = 6
        config.httpShouldSetCookies = false
        config.httpCookieAcceptPolicy = .never
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        var request = URLRequest(url: url)
        request.setValue(Self.browserUserAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("text/html,application/xhtml+xml", forHTTPHeaderField: "Accept")
        let languages = Locale.preferredLanguages.prefix(3).joined(separator: ",")
        if !languages.isEmpty {
            request.setValue(languages, forHTTPHeaderField: "Accept-Language")
        }
        let payload: Payload? = await withCheckedContinuation { continuation in
            lock.lock()
            self.continuation = continuation
            lock.unlock()
            session.dataTask(with: request).resume()
        }
        session.finishTasksAndInvalidate()
        return payload
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        guard let redirected = request.url, ShareLink.httpURL(from: redirected) != nil else {
            completionHandler(nil)
            return
        }
        completionHandler(request)
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            completionHandler(.cancel)
            return
        }
        let mime = (http.mimeType ?? "").lowercased()
        if !mime.isEmpty && !mime.contains("html") && mime != "text/plain" && !mime.contains("xml") {
            completionHandler(.cancel)
            return
        }
        lock.lock()
        contentType = http.value(forHTTPHeaderField: "Content-Type")
        finalURL = http.url
        accepted = true
        lock.unlock()
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive chunk: Data) {
        lock.lock()
        let room = limit - data.count
        if room > 0 {
            data.append(chunk.prefix(room))
        }
        let full = data.count >= limit
        lock.unlock()
        if full {
            dataTask.cancel()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        let continuation = self.continuation
        self.continuation = nil
        let payload = accepted ? Payload(data: data, contentType: contentType, finalURL: finalURL) : nil
        lock.unlock()
        continuation?.resume(returning: payload)
    }

    /// A browser UA: many sites omit `<title>` or swap it for clients that do not look like a browser.
    private static let browserUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
}
