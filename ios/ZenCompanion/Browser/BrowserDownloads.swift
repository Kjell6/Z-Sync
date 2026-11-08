import Foundation
import WebKit

/// Which responses become files instead of pages.
enum BrowserDownloadPolicy {
    static func shouldDownload(isMainFrame: Bool, canShowMIMEType: Bool, contentDisposition: String?) -> Bool {
        guard isMainFrame else { return false }
        if !canShowMIMEType { return true }
        return contentDisposition?
            .trimmingCharacters(in: .whitespaces)
            .lowercased()
            .hasPrefix("attachment") ?? false
    }

    /// A name that stays inside the folder, whatever the server suggested.
    static func safeFileName(_ suggested: String) -> String {
        let name = suggested
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "\\", with: "_")
            .replacingOccurrences(of: ":", with: "_")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "."))
        return name.isEmpty ? "download" : name
    }

    /// `report.pdf`, then `report 2.pdf`, as Files itself would number them.
    static func uniqueFileName(_ name: String, exists: (String) -> Bool) -> String {
        guard exists(name) else { return name }
        let file = name as NSString
        let base = file.deletingPathExtension
        let suffix = file.pathExtension.isEmpty ? "" : ".\(file.pathExtension)"
        var number = 2
        while exists("\(base) \(number)\(suffix)") { number += 1 }
        return "\(base) \(number)\(suffix)"
    }
}

/// Saves downloads into the app's Documents folder, which Files shows under
/// On My iPhone › Z-Sync. WebKit does the loading, so a download keeps the
/// page's sign-in and also works for `blob:` and `data:` addresses. It lives
/// here and not on the page, which may go to sleep while the file loads.
@MainActor
final class BrowserDownloads: NSObject, WKDownloadDelegate {
    static let shared = BrowserDownloads()

    private struct Running {
        let download: WKDownload
        weak var model: MiniBrowserModel?
        var fileName: String
    }

    private var running: [ObjectIdentifier: Running] = [:]

    func adopt(_ download: WKDownload, from model: MiniBrowserModel?) {
        running[ObjectIdentifier(download)] = Running(download: download, model: model, fileName: "")
        download.delegate = self
    }

    func download(
        _ download: WKDownload,
        decideDestinationUsing response: URLResponse,
        suggestedFilename: String,
        completionHandler: @escaping @MainActor @Sendable (URL?) -> Void
    ) {
        let manager = FileManager.default
        let folder = manager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let name = BrowserDownloadPolicy.uniqueFileName(
            BrowserDownloadPolicy.safeFileName(suggestedFilename)
        ) { candidate in
            // WebKit creates the file later, so a running download's name is taken too.
            running.values.contains { $0.fileName == candidate }
                || manager.fileExists(atPath: folder.appendingPathComponent(candidate).path)
        }
        running[ObjectIdentifier(download)]?.fileName = name
        running[ObjectIdentifier(download)]?.model?.showNotice(
            String(localized: "browser.download.started \(name)"),
            symbol: "arrow.down.circle"
        )
        completionHandler(folder.appendingPathComponent(name))
    }

    func downloadDidFinish(_ download: WKDownload) {
        guard let done = running.removeValue(forKey: ObjectIdentifier(download)) else { return }
        done.model?.showNotice(
            String(localized: "browser.download.finished \(done.fileName)"),
            symbol: "checkmark.circle"
        )
    }

    func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
        guard let failed = running.removeValue(forKey: ObjectIdentifier(download)) else { return }
        failed.model?.showNotice(String(localized: "browser.download.failed"))
    }
}
