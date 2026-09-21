import ImageIO
import UIKit

/// Resolves a tab's favicon URL. Direct sync URLs win; otherwise DuckDuckGo's
/// ip3 endpoint. DDG does not build ad profiles from these lookups.
enum FaviconResolver {
    static func url(pageURL: String, directURL: String?) -> URL? {
        if let directURL, let url = URL(string: directURL),
           url.scheme == "https" || url.scheme == "http" {
            return url
        }
        guard let url = URL(string: pageURL), let host = url.host, !host.isEmpty else { return nil }
        let encodedHost = host.addingPercentEncoding(withAllowedCharacters: .urlHostAllowed) ?? host
        return URL(string: "https://icons.duckduckgo.com/ip3/\(encodedHost).ico")
    }

    /// True for `about:`, `chrome:`, `resource:`, `data:` URLs that carry no
    /// web host — they render with a static placeholder icon, not a favicon.
    static func isLocalURL(_ urlString: String) -> Bool {
        guard let url = URL(string: urlString) else { return false }
        guard let scheme = url.scheme?.lowercased() else { return false }
        return scheme != "http" && scheme != "https"
    }

    /// Unique remote icon URLs for every non-static tab in a snapshot.
    static func urls(in snapshot: ZenSnapshot) -> [URL] {
        var seen = Set<URL>()
        var result: [URL] = []
        func consider(_ tab: ZenTab) {
            if tab.hasStaticIcon == true, let icon = tab.icon, !icon.isEmpty { return }
            guard let url = url(pageURL: tab.url, directURL: tab.iconURL) else { return }
            if seen.insert(url).inserted { result.append(url) }
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
    /// (DuckDuckGo) don't decode as a 16×16 smudge — or fail outright,
    /// which `UIImage(data:)` does on some ICO payloads.
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

    private let cache: NSCache<NSURL, UIImage> = {
        let cache = NSCache<NSURL, UIImage>()
        cache.countLimit = 400
        cache.totalCostLimit = 20 * 1024 * 1024
        return cache
    }()
    private let lock = NSLock()
    private var inflight: [URL: Task<UIImage?, Never>] = [:]

    private static let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.urlCache = URLCache(
            memoryCapacity: 8 * 1024 * 1024,
            diskCapacity: 40 * 1024 * 1024,
            diskPath: "zen-favicons"
        )
        config.requestCachePolicy = .returnCacheDataElseLoad
        config.timeoutIntervalForRequest = 12
        config.httpMaximumConnectionsPerHost = 6
        return URLSession(configuration: config)
    }()

    func cached(_ url: URL) -> UIImage? {
        cache.object(forKey: url as NSURL)
    }

    func image(for url: URL) async -> UIImage? {
        if let hit = cached(url) { return hit }
        let task = lock.withLock { () -> Task<UIImage?, Never> in
            if let existing = inflight[url] { return existing }
            let created = Task.detached(priority: .utility) {
                let fetched = await Self.fetch(url)
                let loader = FaviconLoader.shared
                if let fetched {
                    loader.cache.setObject(
                        fetched,
                        forKey: url as NSURL,
                        cost: fetched.cgImage.map { $0.width * $0.height * 4 } ?? 0
                    )
                }
                loader.lock.withLock { loader.inflight[url] = nil }
                return fetched
            }
            inflight[url] = created
            return created
        }
        return await task.value
    }

    func prefetch(from snapshot: ZenSnapshot) {
        let urls = FaviconResolver.urls(in: snapshot)
        guard !urls.isEmpty else { return }
        Task.detached(priority: .utility) {
            await withTaskGroup(of: Void.self) { group in
                var iterator = urls.makeIterator()
                func enqueue() {
                    guard let url = iterator.next() else { return }
                    group.addTask {
                        _ = await FaviconLoader.shared.image(for: url)
                    }
                }
                for _ in 0..<min(6, urls.count) { enqueue() }
                for await _ in group { enqueue() }
            }
        }
    }

    private static func fetch(_ url: URL) async -> UIImage? {
        var request = URLRequest(url: url)
        request.timeoutInterval = 12
        request.cachePolicy = .returnCacheDataElseLoad
        do {
            let (data, response) = try await session.data(for: request)
            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                return nil
            }
            return FaviconDecoder.image(from: data)
        } catch {
            return nil
        }
    }
}
