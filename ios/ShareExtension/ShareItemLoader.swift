import Foundation
import UniformTypeIdentifiers

/// Reads the first http(s) URL from share item providers. Mirrors Android
/// `ShareActivity.extractShared()`: host I/O, not view or sync logic.
enum ShareItemLoader {
    static func firstHTTPURL(from providers: [NSItemProvider]) async -> URL? {
        for provider in providers {
            if provider.canLoadObject(ofClass: URL.self) {
                if let loadedURL = try? await withCheckedThrowingContinuation({ (continuation: CheckedContinuation<URL, Error>) in
                    _ = provider.loadObject(ofClass: URL.self) { item, error in
                        if let url = item as? URL {
                            continuation.resume(returning: url)
                        } else if let error {
                            continuation.resume(throwing: error)
                        } else {
                            continuation.resume(throwing: NSError(domain: "de.kjell.zencompanion", code: -1))
                        }
                    }
                }) {
                    if let url = ShareLink.httpURL(from: loadedURL) { return url }
                }
            }
            for type in [UTType.url, UTType.fileURL] {
                if provider.hasItemConformingToTypeIdentifier(type.identifier) {
                    if let item = try? await provider.loadItem(forTypeIdentifier: type.identifier),
                       let url = ShareLink.httpURL(from: item) {
                        return url
                    }
                }
            }
            if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                if let item = try? await provider.loadItem(forTypeIdentifier: UTType.plainText.identifier),
                   let url = ShareLink.httpURL(from: item) {
                    return url
                }
            }
        }
        return nil
    }
}
