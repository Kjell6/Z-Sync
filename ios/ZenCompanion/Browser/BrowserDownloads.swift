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
        // Control characters too, as on Android: a line break or tab in a
        // name from the server has no place in Files.
        let visible = String(String.UnicodeScalarView(suggested.unicodeScalars.map { scalar in
            scalar.properties.generalCategory == .control ? "_" : scalar
        }))
        let name = visible
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

    /// A download question still waiting for its page's answer.
    struct Question: Equatable {
        let page: ObjectIdentifier
        let address: URL
    }

    /// A second download of the same address from the same page while its
    /// question is still open is dropped instead of asked again.
    static func isDuplicate(address: URL?, page: ObjectIdentifier, waiting: [Question]) -> Bool {
        guard let address else { return false }
        return waiting.contains { $0.page == page && $0.address == address }
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
        /// The page that started it, kept for its question even if the page
        /// moves on before the destination is decided.
        let host: String
        /// The address the file came from, so the same one is asked about once.
        let address: URL?
        /// A window opened only for its file has nothing to show once the
        /// file's question is answered; it closes then.
        let closesPage: Bool
        var fileName: String
        /// The reader's question is on screen and has no answer yet.
        var isAsking = false
        /// The reader said no, or the page was gone: WebKit still reports the
        /// cancellation, and that is not a failed download.
        var declined = false
    }

    private var running: [ObjectIdentifier: Running] = [:]

    func adopt(_ download: WKDownload, from model: MiniBrowserModel?, closingPageWhenAnswered closesPage: Bool = false) {
        running[ObjectIdentifier(download)] = Running(
            download: download,
            model: model,
            host: model?.pageURL?.host ?? model?.webView?.url?.host ?? "",
            address: download.originalRequest?.url,
            closesPage: closesPage,
            fileName: ""
        )
        download.delegate = self
    }

    func download(
        _ download: WKDownload,
        decideDestinationUsing response: URLResponse,
        suggestedFilename: String,
        completionHandler: @escaping @MainActor @Sendable (URL?) -> Void
    ) {
        let id = ObjectIdentifier(download)
        guard let entry = running[id] else {
            completionHandler(nil)
            return
        }
        // A page that is asleep has no screen to show the question on; the
        // file is dropped as if the page were gone.
        guard let model = entry.model, model.webView != nil else {
            decline(download, completionHandler)
            return
        }
        // A page already asking about this address asks once: the newer copy
        // is dropped without a second question.
        let waiting = running.compactMap { key, other -> BrowserDownloadPolicy.Question? in
            guard key != id, other.isAsking, let page = other.model, let address = other.address else { return nil }
            return BrowserDownloadPolicy.Question(page: ObjectIdentifier(page), address: address)
        }
        guard !BrowserDownloadPolicy.isDuplicate(
            address: entry.address,
            page: ObjectIdentifier(model),
            waiting: waiting
        ) else {
            // The page's first question about this address is still the
            // reader's to answer, so a duplicate goes without closing a
            // window that opened only for its file.
            decline(download, completionHandler, closingPage: false)
            return
        }

        let manager = FileManager.default
        let folder = manager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        // The question names the file as it will arrive, before Files numbers
        // a second copy.
        let suggestedName = BrowserDownloadPolicy.safeFileName(suggestedFilename)
        let name = BrowserDownloadPolicy.uniqueFileName(suggestedName) { candidate in
            // WebKit creates the file later, so a running download's name is taken too.
            running.values.contains { $0.fileName == candidate }
                || manager.fileExists(atPath: folder.appendingPathComponent(candidate).path)
        }
        running[id]?.fileName = name
        running[id]?.isAsking = true

        let answer = DialogAnswer<Bool>(fallback: false) { [weak self] approved in
            MainActor.assumeIsolated {
                guard let self, self.running[id] != nil else {
                    completionHandler(nil)
                    return
                }
                self.running[id]?.isAsking = false
                guard approved else {
                    self.decline(download, completionHandler)
                    return
                }
                self.running[id]?.model?.showNotice(
                    String(localized: "browser.download.started \(name)"),
                    symbol: "arrow.down.circle"
                )
                completionHandler(folder.appendingPathComponent(name))
                self.closeFileWindow(id)
            }
        }
        model.present(.download(host: entry.host, fileName: suggestedName, answer))
    }

    /// Answers WebKit with no destination, which cancels the download. The
    /// entry stays a moment so the cancellation it reports is not read as a
    /// failed download. A window opened only for its file closes with the
    /// answer, unless this copy was only a duplicate of one already being
    /// asked about: then its page still has a question to show.
    private func decline(
        _ download: WKDownload,
        _ completionHandler: @escaping @MainActor @Sendable (URL?) -> Void,
        closingPage: Bool = true
    ) {
        let id = ObjectIdentifier(download)
        running[id]?.isAsking = false
        running[id]?.declined = true
        // The name is free again: nothing was written.
        running[id]?.fileName = ""
        completionHandler(nil)
        if closingPage { closeFileWindow(id) }
        // WebKit reports the cancellation through `didFail`; this is only a
        // backstop for a report that never comes.
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(5))
            guard let self, self.running[id]?.declined == true else { return }
            self.running.removeValue(forKey: id)
        }
    }

    /// A window that opened only for its file leaves nothing to show once the
    /// file is answered. The page closes then, never while it is already
    /// asleep (the browser ending must not drop its tabs).
    private func closeFileWindow(_ id: ObjectIdentifier) {
        guard let entry = running[id], entry.closesPage,
              let model = entry.model, model.webView != nil else { return }
        model.host?.pageRequestedClose(model)
    }

    func downloadDidFinish(_ download: WKDownload) {
        guard let done = running.removeValue(forKey: ObjectIdentifier(download)) else { return }
        guard !done.declined else { return }
        done.model?.showNotice(
            String(localized: "browser.download.finished \(done.fileName)"),
            symbol: "checkmark.circle"
        )
        if done.closesPage, let model = done.model, model.webView != nil {
            model.host?.pageRequestedClose(model)
        }
    }

    func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
        guard let failed = running.removeValue(forKey: ObjectIdentifier(download)) else { return }
        // A download the reader declined (or that never had a page to ask)
        // ends here as a cancellation. That is not a failure to report.
        guard !failed.declined else { return }
        failed.model?.showNotice(String(localized: "browser.download.failed"))
        if failed.closesPage, let model = failed.model, model.webView != nil {
            model.host?.pageRequestedClose(model)
        }
    }
}
