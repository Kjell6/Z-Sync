import ImageIO
import UIKit

struct FaviconRequest: Hashable {
    var pageURL: String
    var directURL: String?
    var dataURL: String?
    var cacheKey: String
}

/// Where a tab's favicon comes from: an inline `data:` icon, a direct URL, or
/// the site itself. There is no third-party icon host.
enum FaviconSource {
    case data(String, key: String)
    case direct(String, key: String)
    case unknown
}

/// Inline sync icon, direct sync icon, otherwise the site's own icon.
enum FaviconResolver {
    static func url(pageURL: String, directURL: String?, dataURL: String? = nil) -> URL? {
        guard let directURL, let url = URL(string: directURL),
              url.scheme == "https" || url.scheme == "http" else { return nil }
        return url
    }

    /// What the loader should use. Zen syncs the browser's own favicon as a
    /// `data:` URI in the tab's `icon` field for most desktop tabs; that is
    /// decoded on device (no network), so it beats any remote lookup.
    static func source(pageURL: String, directURL: String?, dataURL: String? = nil) -> FaviconSource {
        if let data = usableDataURL(dataURL) {
            return .data(data, key: "data:\(pageURL)")
        }
        if let direct = url(pageURL: pageURL, directURL: directURL) {
            return .direct(direct.absoluteString, key: "direct:\(direct.absoluteString)")
        }
        guard let document = FaviconDiscovery.documentURL(from: pageURL),
              let site = FaviconDiscovery.siteKey(for: document) else { return .unknown }
        return .direct(document.absoluteString, key: "site:\(site)")
    }

    static func cacheKey(pageURL: String, directURL: String?, dataURL: String? = nil) -> String? {
        switch source(pageURL: pageURL, directURL: directURL, dataURL: dataURL) {
        case .data(_, let key): return key
        case .direct(_, let key): return key
        case .unknown: return nil
        }
    }

    static func usableDataURL(_ raw: String?) -> String? {
        guard let clean = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              clean.hasPrefix("data:image/") else { return nil }
        return clean
    }

    /// True for `about:`, `chrome:`, `resource:`, `data:` URLs that carry no
    /// web host — they render with a static placeholder icon, not a favicon.
    static func isLocalURL(_ urlString: String) -> Bool {
        guard let url = URL(string: urlString) else { return false }
        guard let scheme = url.scheme?.lowercased() else { return false }
        return scheme != "http" && scheme != "https"
    }

    /// One request per distinct icon. Static emoji icons are not fetched.
    static func requests(in snapshot: ZenSnapshot) -> [FaviconRequest] {
        var seen = Set<String>()
        var result: [FaviconRequest] = []
        func consider(_ tab: ZenTab) {
            // Zen static icons (emoji/vector SVG) render via ZenIconDecoder,
            // never the favicon loader. Their payload is also a data: URL, so
            // this must be skipped before the data-url check below.
            if tab.hasStaticIcon == true, let icon = tab.icon, !icon.isEmpty { return }
            let dataURL = FaviconResolver.usableDataURL(tab.icon)
            guard let key = cacheKey(pageURL: tab.url, directURL: tab.iconURL, dataURL: dataURL) else { return }
            guard seen.insert(key).inserted else { return }
            result.append(FaviconRequest(pageURL: tab.url, directURL: tab.iconURL, dataURL: dataURL, cacheKey: key))
        }
        for space in snapshot.spaces {
            for item in space.pinned {
                switch item {
                case .tab(let tab): consider(tab)
                case .folder(let folder): folder.tabs.forEach(consider)
                case .split(let split): split.tabs.forEach(consider)
                }
            }
            for item in space.tabs {
                switch item {
                case .tab(let tab): consider(tab)
                case .folder(let folder): folder.tabs.forEach(consider)
                case .split(let split): split.tabs.forEach(consider)
                }
            }
        }
        for tabs in snapshot.essentials.values { tabs.forEach(consider) }
        return result
    }
}

enum FaviconDecoder {
    /// Picks the largest reasonably-sized frame so multi-resolution ICOs
    /// don't decode as a 16×16 smudge — or fail outright, which
    /// `UIImage(data:)` does on some ICO payloads.
    static func image(from data: Data) -> UIImage? {
        guard data.count > 8 else { return nil }
        if let source = CGImageSourceCreateWithData(data as CFData, nil) {
            let count = CGImageSourceGetCount(source)
            var best: CGImage?
            var bestArea = 0
            for index in 0..<max(count, 1) {
                guard let cgImage = CGImageSourceCreateImageAtIndex(source, index, nil) else { continue }
                let area = cgImage.width * cgImage.height
                if area > bestArea {
                    bestArea = area
                    best = cgImage
                }
            }
            if let best { return UIImage(cgImage: best) }
        }
        return UIImage(data: data)
    }
}

/// In-memory decoded-image cache plus coalesced fetches. `AsyncImage` is not
/// used: it cancels in lazy pager pages, does not share results across the
/// duplicate Essentials grids of same-container spaces, and frequently stays
/// stuck on the placeholder after a cancelled load.
final class FaviconLoader: @unchecked Sendable {
    static let shared = FaviconLoader()

    private let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 400
        cache.totalCostLimit = 20 * 1024 * 1024
        return cache
    }()
    private let lock = NSLock()
    private var inflight: [String: Task<UIImage?, Never>] = [:]
    /// cacheKey → time of last failure. Entries expire so a transient blip does
    /// not hide an icon for the whole session.
    private var failed: [String: Date] = [:]
    /// Host key to the icon URL that decoded. Cold starts skip the HTML fetch.
    private var resolved: [String: String] = [:]
    private var indexLoaded = false

    private static let maxIconBytes = 512 * 1024
    private static let failedTTL: TimeInterval = 60
    /// Own disk store for icon bytes (raw or rasterized SVG). URLCache is not
    /// used: we need a TTL, a size cap, and control over SVG rasters.
    private static let diskMaxBytes: Int64 = 40 * 1024 * 1024
    private static let diskTTL: TimeInterval = 30 * 24 * 60 * 60

    func cached(pageURL: String, directURL: String?, dataURL: String? = nil) -> UIImage? {
        guard let key = FaviconResolver.cacheKey(pageURL: pageURL, directURL: directURL, dataURL: dataURL) else { return nil }
        return cache.object(forKey: key as NSString)
    }

    func image(pageURL: String, directURL: String?, dataURL: String? = nil) async -> UIImage? {
        guard let key = FaviconResolver.cacheKey(pageURL: pageURL, directURL: directURL, dataURL: dataURL) else { return nil }
        if let hit = cache.object(forKey: key as NSString) { return hit }
        let task = lock.withLock { () -> Task<UIImage?, Never> in
            if let existing = inflight[key] { return existing }
            if let when = failed[key] {
                if Date().timeIntervalSince(when) < Self.failedTTL {
                    return Task { nil }
                }
                failed[key] = nil
            }
            let created = Task.detached(priority: .utility) {
                let fetched = await Self.load(pageURL: pageURL, directURL: directURL, dataURL: dataURL)
                let loader = FaviconLoader.shared
                loader.lock.withLock {
                    if let fetched {
                        loader.cache.setObject(
                            fetched,
                            forKey: key as NSString,
                            cost: fetched.cgImage.map { $0.width * $0.height * 4 } ?? 0
                        )
                        loader.failed[key] = nil
                    } else {
                        loader.failed[key] = Date()
                    }
                    loader.inflight[key] = nil
                }
                return fetched
            }
            inflight[key] = created
            return created
        }
        return await task.value
    }

    func prefetch(from snapshot: ZenSnapshot) {
        let requests = FaviconResolver.requests(in: snapshot)
        guard !requests.isEmpty else { return }
        lock.withLock { failed.removeAll() }
        pruneIndex(keeping: requests)
        Task.detached(priority: .utility) {
            await withTaskGroup(of: Void.self) { group in
                var iterator = requests.makeIterator()
                func enqueue() {
                    guard let request = iterator.next() else { return }
                    group.addTask {
                        _ = await FaviconLoader.shared.image(
                            pageURL: request.pageURL,
                            directURL: request.directURL,
                            dataURL: request.dataURL
                        )
                    }
                }
                for _ in 0..<min(6, requests.count) { enqueue() }
                for await _ in group { enqueue() }
            }
        }
    }

    private static func load(pageURL: String, directURL: String?, dataURL: String?) async -> UIImage? {
        // A synced data: icon is a real image. Raster payloads (PNG/ICO) decode
        // in memory: instant, offline. An SVG payload is kept as a last resort,
        // because a live network image is usually crisper.
        let svgData = dataURL.flatMap { isSvgData($0) ? $0 : nil }
        if let dataURL, svgData == nil, let image = decodeDataURL(dataURL) { return image }
        if let direct = FaviconResolver.url(pageURL: pageURL, directURL: directURL),
           let image = await fetchCandidate(direct) {
            return image
        }
        // One key per tab and one per site; the site key keys the persisted index
        // so the next launch skips the HTML fetch for every tab on that site.
        let key = FaviconResolver.cacheKey(pageURL: pageURL, directURL: directURL, dataURL: dataURL) ?? ""
        let siteKey = FaviconResolver.cacheKey(pageURL: pageURL, directURL: nil, dataURL: nil) ?? key
        let page = FaviconDiscovery.documentURL(from: pageURL)
        if let page, !FaviconDiscovery.isLoopback(page) {
            if let remembered = FaviconLoader.shared.rememberedURL(for: siteKey),
               let image = await fetchCandidate(remembered) {
                return image
            }
            let candidates: [URL]
            if FaviconDiscovery.hasKnownIcon(for: page) {
                // The logged-out document is a sign-in wall or the wrong mark.
                // One small file, no HTML. The tab does not have to be opened here.
                candidates = FaviconDiscovery.candidates(html: nil, finalURL: nil, pageURL: page)
            } else if let document = await fetchDocument(page) {
                candidates = FaviconDiscovery.candidates(
                    html: document.html,
                    finalURL: document.finalURL,
                    pageURL: page
                )
            } else {
                candidates = FaviconDiscovery.candidates(html: nil, finalURL: nil, pageURL: page)
            }
            for url in candidates.prefix(maxCandidates) {
                if let image = await fetchCandidate(url) {
                    FaviconLoader.shared.remember(siteKey, url: url)
                    return image
                }
            }
        }
        // Nothing fetchable: render the synced SVG icon locally.
        if let svgData, let svg = Self.svgString(from: svgData),
           let image = await SvgRasterizer.rasterize(svg, size: 128) {
            return image
        }
        // Last resort: the site blocks scraping or has no discoverable icon
        // (login redirect, 403, no <link>). Ask a public logo service for the
        // domain — but never for a private/LAN host. See docs/privacy.md.
        if let page, !FaviconDiscovery.isLoopback(page),
           let host = page.host?.lowercased(), !host.isEmpty, !isPrivateHost(host),
           let service = URL(string: "https://www.google.com/s2/favicons?domain=\(host)&sz=128"),
           let image = await fetchImage(service) {
            FaviconLoader.shared.remember(siteKey, url: service)
            return image
        }
        return nil
    }

    private static let maxCandidates = 8

    /**
     * Fetch one candidate icon: disk cache first, then one capped download,
     * accepting raster bytes or SVG markup. The caller does not have to know
     * which it is, so a declared SVG served from a non-`.svg` URL still works.
     */
    private static func fetchCandidate(_ url: URL) async -> UIImage? {
        if let cached = cachedImage(url) { return cached }
        guard let data = await fetchBytes(url) else { return nil }
        if let raster = FaviconDecoder.image(from: data) {
            storeImage(url, data: data)
            return raster
        }
        guard let svg = String(data: data, encoding: .utf8),
              svg.range(of: "<svg", options: .caseInsensitive) != nil,
              let png = await SvgRasterizer.rasterize(svg, size: 128) else { return nil }
        storeImage(url, png: png)
        return png
    }

    /// Capped GET (512 KB, capped while streaming) with no cookies.
    private static func fetchBytes(_ url: URL) async -> Data? {
        await CappedDataFetcher(limit: maxIconBytes).fetch(url, userAgent: browserUserAgent, timeout: 12)
    }

    private static func isSvgData(_ dataURL: String) -> Bool {
        dataURL.lowercased().hasPrefix("data:image/svg")
    }

    /// Decode a `data:image/…` payload: raster (base64 or percent) or a marker.
    private static func decodeDataURL(_ dataURL: String) -> UIImage? {
        guard let data = dataURLBytes(dataURL), !data.isEmpty, data.count <= maxIconBytes else { return nil }
        return FaviconDecoder.image(from: data)
    }

    private static func dataURLBytes(_ dataURL: String) -> Data? {
        guard let comma = dataURL.firstIndex(of: ",") else { return nil }
        let header = dataURL[..<comma]
        let payload = String(dataURL[dataURL.index(after: comma)...])
        if header.contains(";base64") {
            return Data(base64Encoded: payload, options: .ignoreUnknownCharacters)
        }
        return percentData(payload)
    }

    /// Percent-decode to raw bytes, byte for byte (no `+`-as-space, no charset loss).
    private static func percentData(_ text: String) -> Data {
        var out = Data()
        var i = text.startIndex
        while i < text.endIndex {
            let c = text[i]
            if c == "%",
               let i1 = text.index(i, offsetBy: 1, limitedBy: text.endIndex),
               let i2 = text.index(i, offsetBy: 2, limitedBy: text.endIndex),
               i2 < text.endIndex,
               let byte = UInt8(String(text[i1]) + String(text[i2]), radix: 16) {
                out.append(byte)
                i = text.index(after: i2)
                continue
            }
            out.append(contentsOf: Array(String(c).utf8))
            i = text.index(after: i)
        }
        return out
    }

    /// Decode a `data:image/svg+xml` payload (base64 or percent-encoded) to markup.
    private static func svgString(from dataURL: String) -> String? {
        guard let data = dataURLBytes(dataURL) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Loopback, RFC1918/LAN, `.local`/`.internal` and single-label hosts: keep off Google.
    private static func isPrivateHost(_ host: String) -> Bool {
        let h = host.trimmingCharacters(in: CharacterSet(charactersIn: "[]")).lowercased()
        if h.isEmpty || h == "localhost" { return true }
        if h.allSatisfy({ $0.isNumber || $0 == "." }) {
            let parts = h.split(separator: ".").compactMap { Int($0) }
            if parts.count == 4 {
                let (a, b) = (parts[0], parts[1])
                return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && (16...31).contains(b))
                    || (a == 169 && b == 254) || (a == 100 && (64...127).contains(b)) || a == 0
            }
            return true
        }
        if h.contains(":") { return true }
        let suffixes = [".local", ".internal", ".lan", ".home", ".home.arpa", ".localhost"]
        if suffixes.contains(where: { h.hasSuffix($0) }) { return true }
        return !h.contains(".")
    }

    private func rememberedURL(for key: String) -> URL? {
        lock.lock()
        loadIndexIfNeeded()
        let raw = resolved[key]
        lock.unlock()
        return raw.flatMap { URL(string: $0) }
    }

    private func remember(_ key: String, url: URL) {
        guard !key.isEmpty else { return }
        // Snapshot and write under the lock: two concurrent loads otherwise
        // interleave whole-file rewrites and the older snapshot wins.
        lock.lock()
        defer { lock.unlock() }
        loadIndexIfNeeded()
        resolved[key] = url.absoluteString
        writeIndexLocked()
    }

    /// Drop index entries for sites no longer present in the snapshot.
    private func pruneIndex(keeping requests: [FaviconRequest]) {
        let keep = Set(requests.compactMap {
            FaviconResolver.cacheKey(pageURL: $0.pageURL, directURL: nil, dataURL: nil)
        })
        lock.lock()
        defer { lock.unlock() }
        loadIndexIfNeeded()
        let before = resolved.count
        resolved = resolved.filter { keep.contains($0.key) }
        if resolved.count != before { writeIndexLocked() }
    }

    private func writeIndexLocked() {
        guard let file = Self.indexFile,
              let data = try? JSONSerialization.data(withJSONObject: resolved) else { return }
        try? data.write(to: file, options: .atomic)
    }

    private func loadIndexIfNeeded() {
        guard !indexLoaded else { return }
        indexLoaded = true
        guard let file = Self.indexFile,
              let data = try? Data(contentsOf: file),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: String] else { return }
        resolved = object
    }

    private static var indexFile: URL? {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?
            .appendingPathComponent("zen-favicon-index.json")
    }

    /// Raster-only fetch (direct URL, logo service): disk cache, then network.
    private static func fetchImage(_ url: URL) async -> UIImage? {
        if let cached = cachedImage(url) { return cached }
        guard let data = await fetchBytes(url) else { return nil }
        guard let image = FaviconDecoder.image(from: data) else { return nil }
        storeImage(url, data: data)
        return image
    }

    // MARK: - Icon disk store (TTL + size cap)

    private static var storeDir: URL? {
        guard let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else { return nil }
        let dir = base.appendingPathComponent("zen-favicon-store", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }

    private static func storeFile(_ url: URL) -> URL? {
        guard let dir = storeDir else { return nil }
        // A tiny FNV-1a is enough to key the cache; collisions are harmless.
        var h: UInt64 = 0xcbf29ce484222325
        for b in url.absoluteString.utf8 { h = (h ^ UInt64(b)) &* 0x100000001b3 }
        return dir.appendingPathComponent(String(h, radix: 16) + ".bin")
    }

    private static func cachedImage(_ url: URL) -> UIImage? {
        guard let file = storeFile(url), FileManager.default.fileExists(atPath: file.path) else { return nil }
        let modified = (try? file.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
        if let modified, Date().timeIntervalSince(modified) > diskTTL {
            try? FileManager.default.removeItem(at: file)
            return nil
        }
        guard let data = try? Data(contentsOf: file), let image = FaviconDecoder.image(from: data) else {
            try? FileManager.default.removeItem(at: file)
            return nil
        }
        return image
    }

    private static func storeImage(_ url: URL, data: Data) {
        guard let file = storeFile(url) else { return }
        try? data.write(to: file, options: .atomic)
        trimDiskStore()
    }

    private static func storeImage(_ url: URL, png: UIImage) {
        guard let file = storeFile(url), let data = png.pngData() else { return }
        try? data.write(to: file, options: .atomic)
        trimDiskStore()
    }

    /// Keeps the store under [diskMaxBytes], dropping the oldest files first.
    private static func trimDiskStore() {
        guard let dir = storeDir,
              let files = try? FileManager.default.contentsOfDirectory(
                at: dir, includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey]
              ) else { return }
        var total: Int64 = 0
        var entries: [(URL, Date, Int64)] = []
        for file in files {
            let values = try? file.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey])
            let size = Int64(values?.fileSize ?? 0)
            total += size
            entries.append((file, values?.contentModificationDate ?? .distantPast, size))
        }
        guard total > diskMaxBytes else { return }
        for (file, _, size) in entries.sorted(by: { $0.1 < $1.1 }) {
            if total <= diskMaxBytes { break }
            try? FileManager.default.removeItem(at: file)
            total -= size
        }
    }

    /// Stops after 64KB. Icon links sit in `<head>`; a synced tab must not
    /// download the whole page the user never opened here.
    private static func fetchDocument(_ url: URL) async -> (html: String, finalURL: URL)? {
        await DocumentPrefixFetcher().fetch(url, userAgent: browserUserAgent)
    }

    /// A browser UA: many sites omit icon links for clients that do not look like a browser.
    private static let browserUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
}

/// Downloads a URL with a hard byte cap enforced while streaming, so a hostile
/// or huge response cannot balloon memory. No cookies. Follows redirects.
private final class CappedDataFetcher: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Data?, Never>?
    private var data = Data()
    private var accepted = false
    private let limit: Int

    init(limit: Int) { self.limit = limit }

    func fetch(_ url: URL, userAgent: String, timeout: TimeInterval) async -> Data? {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = timeout + 4
        config.httpShouldSetCookies = false
        config.httpCookieAcceptPolicy = .never
        config.httpCookieStorage = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        var request = URLRequest(url: url)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        let payload: Data? = await withCheckedContinuation { continuation in
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
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              http.expectedContentLength <= Int64(limit) else {
            completionHandler(.cancel)
            return
        }
        lock.lock()
        accepted = true
        lock.unlock()
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive chunk: Data) {
        lock.lock()
        let room = limit - data.count
        if room > 0 { data.append(chunk.prefix(room)) }
        let full = data.count >= limit
        lock.unlock()
        if full { dataTask.cancel() }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        let continuation = self.continuation
        self.continuation = nil
        let payload: Data? = accepted && !data.isEmpty ? data : nil
        lock.unlock()
        continuation?.resume(returning: payload)
    }
}

/// Reads at most 64KB of an http(s) document, then cancels. No cookies.
private final class DocumentPrefixFetcher: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<(html: String, finalURL: URL)?, Never>?
    private var data = Data()
    private var contentType: String?
    private var finalURL: URL?
    private var accepted = false
    private let limit = 65_536

    func fetch(_ url: URL, userAgent: String) async -> (html: String, finalURL: URL)? {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 5
        config.timeoutIntervalForResource = 8
        config.httpShouldSetCookies = false
        config.httpCookieAcceptPolicy = .never
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        var request = URLRequest(url: url)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("text/html,application/xhtml+xml", forHTTPHeaderField: "Accept")
        let payload: (html: String, finalURL: URL)? = await withCheckedContinuation { continuation in
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
        finalURL = http.url ?? dataTask.originalRequest?.url
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
        let payload: (html: String, finalURL: URL)?
        if accepted, let finalURL {
            let html = PageTitle.htmlString(from: data, contentType: contentType)
            payload = (html, finalURL)
        } else {
            payload = nil
        }
        lock.unlock()
        continuation?.resume(returning: payload)
    }
}
