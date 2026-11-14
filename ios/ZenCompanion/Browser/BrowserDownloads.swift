import Foundation
import Observation
import UIKit
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

    /// A short reason for a download that failed, from what went wrong. The
    /// system's own message can name things the reader cannot act on, so only
    /// the kinds worth telling apart get their own line.
    static func failureReason(_ error: Error) -> String {
        let problem = error as NSError
        guard problem.domain == NSURLErrorDomain else {
            return String(localized: "browser.download.failed")
        }
        switch URLError.Code(rawValue: problem.code) {
        case .notConnectedToInternet, .networkConnectionLost, .timedOut,
             .cannotConnectToHost, .cannotFindHost, .dnsLookupFailed:
            return String(localized: "browser.download.reason.network")
        case .badServerResponse, .zeroByteResource, .cannotDecodeContentData:
            return String(localized: "browser.download.reason.server")
        default:
            return String(localized: "browser.download.failed")
        }
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

/// One file the browser is showing: still arriving, waiting for its folder,
/// or a result on its way out. It belongs to the browser and not to the page
/// that started it, so it still shows after a file-only window closed.
struct BrowserDownloadTask: Identifiable, Equatable {
    enum Stage: Equatable {
        case fetching
        /// Arrived: the reader is choosing the folder it goes to.
        case choosingFolder
        case saved
        case failed(String?)
    }

    let id: UUID
    var fileName: String
    var stage: Stage
    var bytesReceived: Int64
    /// -1 when the server sent no length, as `NSProgress.totalUnitCount` does.
    var totalBytes: Int64

    /// How far this one file has come, or nil while its size is unknown.
    var fraction: Double? {
        guard totalBytes > 0 else { return nil }
        return min(1, max(0, Double(bytesReceived) / Double(totalBytes)))
    }

    /// A file that can still be cancelled: a result is over.
    var isActive: Bool {
        switch stage {
        case .fetching, .choosingFolder: true
        case .saved, .failed: false
        }
    }
}

/// The line the download capsule shows, as plain data: which files it speaks
/// for and what it says about them.
enum BrowserDownloadCapsule {
    enum Kind: Equatable {
        case fetching
        case choosingFolder
        case saved
        case failed
    }

    struct Content: Equatable {
        let kind: Kind
        /// The files the line speaks for; its cancel ends exactly these.
        let tasks: [BrowserDownloadTask]
        /// The reason of the failure, when there is one to name.
        let reason: String?

        var count: Int { tasks.count }
        var fileName: String { tasks.first?.fileName ?? "" }
        var taskIDs: [UUID] { tasks.map(\.id) }
        /// The arrival of the files it speaks for; nil is a line that cannot
        /// say how far it is.
        var fraction: Double? { BrowserDownloadCapsule.combinedFraction(tasks) }
    }

    /// The files the capsule speaks for, or nil when there is nothing to say.
    /// Work in flight wins over a result that is fading out, so the line
    /// always names something the reader can still act on.
    static func content(for tasks: [BrowserDownloadTask]) -> Content? {
        guard !tasks.isEmpty else { return nil }
        if !tasks.filter({ $0.stage == .fetching }).isEmpty {
            return Content(kind: .fetching, tasks: tasks.filter { $0.stage == .fetching }, reason: nil)
        }
        if !tasks.filter({ $0.stage == .choosingFolder }).isEmpty {
            return Content(
                kind: .choosingFolder,
                tasks: tasks.filter { $0.stage == .choosingFolder },
                reason: nil
            )
        }
        let failed = tasks.filter(Self.isFailed)
        if !failed.isEmpty {
            var reason: String?
            if case .failed(let text) = failed[0].stage { reason = text }
            return Content(kind: .failed, tasks: failed, reason: reason)
        }
        return Content(kind: .saved, tasks: tasks, reason: nil)
    }

    private static func isFailed(_ task: BrowserDownloadTask) -> Bool {
        if case .failed = task.stage { return true }
        return false
    }

    /// The share of all the bytes at once, or nil while any size is unknown:
    /// one unknown file makes the whole line honest about not knowing.
    static func combinedFraction(_ tasks: [BrowserDownloadTask]) -> Double? {
        guard !tasks.isEmpty else { return nil }
        var received: Double = 0
        var total: Double = 0
        for task in tasks {
            guard task.totalBytes > 0 else { return nil }
            received += Double(max(0, min(task.bytesReceived, task.totalBytes)))
            total += Double(task.totalBytes)
        }
        guard total > 0 else { return nil }
        return min(1, received / total)
    }

    /// "42%" for a line that knows how far it is.
    static func percentLabel(_ fraction: Double) -> String {
        let percent = Int((min(1, max(0, fraction)) * 100).rounded())
        return String(localized: "browser.download.percent \(percent)")
    }
}

/// The downloads the browser shows, and every way one of them changes. Plain
/// data, so the order of the states is testable without WebKit.
struct BrowserDownloadLedger: Equatable {
    private(set) var tasks: [BrowserDownloadTask] = []

    /// A file begins arriving. A name another active file already carries is
    /// numbered, as Files would number the second copy.
    @discardableResult
    mutating func start(id: UUID = UUID(), fileName: String) -> BrowserDownloadTask {
        let name = BrowserDownloadPolicy.uniqueFileName(fileName) { candidate in
            tasks.contains { $0.isActive && $0.fileName == candidate }
        }
        let task = BrowserDownloadTask(
            id: id,
            fileName: name,
            stage: .fetching,
            bytesReceived: 0,
            totalBytes: -1
        )
        tasks.append(task)
        return task
    }

    /// New bytes, as WebKit reports them. A total of -1 stays unknown.
    mutating func update(id: UUID, bytesReceived: Int64, totalBytes: Int64) {
        guard let index = tasks.firstIndex(where: { $0.id == id }) else { return }
        tasks[index].bytesReceived = max(0, bytesReceived)
        tasks[index].totalBytes = totalBytes
    }

    /// The file arrived and its save screen is up.
    mutating func chooseFolder(id: UUID) {
        guard let index = tasks.firstIndex(where: { $0.id == id }) else { return }
        tasks[index].stage = .choosingFolder
    }

    /// The reader saved it, maybe under the name they chose there.
    mutating func saved(id: UUID, name: String?) {
        guard let index = tasks.firstIndex(where: { $0.id == id }) else { return }
        if let name, !name.isEmpty { tasks[index].fileName = name }
        tasks[index].stage = .saved
    }

    mutating func fail(id: UUID, reason: String?) {
        guard let index = tasks.firstIndex(where: { $0.id == id }) else { return }
        tasks[index].stage = .failed(reason)
    }

    /// A file that never started still says why, on whatever page is in front.
    @discardableResult
    mutating func fail(name: String, reason: String?) -> UUID {
        let task = start(fileName: name)
        fail(id: task.id, reason: reason)
        return task.id
    }

    mutating func remove(id: UUID) {
        tasks.removeAll { $0.id == id }
    }
}

/// Saves downloads wherever the reader picks in Files. WebKit fetches each file
/// into a folder of its own in the temporary directory, then the system's save
/// screen moves it to the folder the reader chooses: WebKit wants a destination
/// before the bytes arrive, so the file has to land somewhere writable first,
/// and only the picker knows where the reader wants it. The capsule the reader
/// sees while this happens belongs to the browser and not to the page, which
/// may close (or go to sleep) while the file still loads.
@MainActor
@Observable
final class BrowserDownloads: NSObject, WKDownloadDelegate, UIDocumentPickerDelegate {
    static let shared = BrowserDownloads()

    /// How long "Saved" or the reason it failed stays on screen.
    private static let resultDuration: Duration = .seconds(5)

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
        var fileName = ""
        /// Where WebKit writes the file until the reader chooses a folder.
        var temporaryFolder: URL?
        /// The capsule's entry for this file.
        var taskID: UUID?
        /// The reader's question is on screen and has no answer yet.
        var isAsking = false
        /// The reader said no, or the page was gone: WebKit still reports the
        /// cancellation, and that is not a failed download.
        var declined = false
        /// The reader cancelled the file from the capsule.
        var cancelled = false
        var progressObservations: [NSKeyValueObservation] = []
    }

    /// A file waiting in the system's save screen, which is kept here so it
    /// cannot go before its answer does.
    private struct FolderChoice {
        let download: ObjectIdentifier
        let taskID: UUID
        let folder: URL
        let picker: UIDocumentPickerViewController
    }

    private var running: [ObjectIdentifier: Running] = [:]
    private var ledger = BrowserDownloadLedger()
    /// Keyed by the save screen on display, so its answer finds its file.
    private var folderChoices: [ObjectIdentifier: FolderChoice] = [:]
    /// Results still on screen, which go after a few seconds.
    private var resultTasks: [UUID: Task<Void, Never>] = [:]

    /// What the capsule above the bar shows, whichever page is in front.
    var tasks: [BrowserDownloadTask] { ledger.tasks }

    func adopt(_ download: WKDownload, from model: MiniBrowserModel?, closingPageWhenAnswered closesPage: Bool = false) {
        running[ObjectIdentifier(download)] = Running(
            download: download,
            model: model,
            host: model?.pageURL?.host ?? model?.webView?.url?.host ?? "",
            address: download.originalRequest?.url,
            closesPage: closesPage
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
        let suggestedName = BrowserDownloadPolicy.safeFileName(suggestedFilename)
        guard let entry = running[id] else {
            completionHandler(nil)
            return
        }
        // A page that is asleep has no screen to show the question on; the
        // file is dropped, and the capsule says why instead of staying silent.
        guard let model = entry.model, model.webView != nil else {
            fail(
                download,
                name: suggestedName,
                reason: String(localized: "browser.download.page_asleep"),
                completionHandler
            )
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

        // The question names the file as it will arrive. Files itself numbers a
        // second copy at the folder the reader picks, so only a name another
        // download in flight already carries is numbered here.
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
                guard let folder = Self.makeTemporaryFolder() else {
                    self.fail(
                        download,
                        name: suggestedName,
                        reason: String(localized: "browser.download.reason.save"),
                        completionHandler
                    )
                    return
                }
                let task = self.ledger.start(fileName: suggestedName)
                self.running[id]?.fileName = task.fileName
                self.running[id]?.temporaryFolder = folder
                self.running[id]?.taskID = task.id
                self.observeProgress(download, task: task.id)
                completionHandler(folder.appendingPathComponent(task.fileName))
                // A file-only window has nothing left to show; the capsule is
                // the browser's, so it stays on the page that asked.
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
            self.removeTemporaryFolder(self.running[id]?.temporaryFolder)
            self.running.removeValue(forKey: id)
        }
    }

    /// Nothing can be written, so WebKit is told there is no destination and
    /// the capsule says why. The question keeps no answer to wait for.
    private func fail(
        _ download: WKDownload,
        name: String,
        reason: String,
        _ completionHandler: @escaping @MainActor @Sendable (URL?) -> Void
    ) {
        let id = ObjectIdentifier(download)
        let task = running[id]?.taskID ?? ledger.start(fileName: name).id
        running[id]?.taskID = task
        ledger.fail(id: task, reason: reason)
        scheduleForget(task)
        removeTemporaryFolder(running[id]?.temporaryFolder)
        decline(download, completionHandler)
    }

    /// A folder of its own for each download, so two files of the same name can
    /// wait for their save screens at once without touching each other.
    private static func makeTemporaryFolder() -> URL? {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("BrowserDownloads", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            return folder
        } catch {
            return nil
        }
    }

    private func removeTemporaryFolder(_ folder: URL?) {
        guard let folder else { return }
        try? FileManager.default.removeItem(at: folder)
    }

    /// WebKit reports bytes on its own thread; the capsule is main-actor state.
    private func observeProgress(_ download: WKDownload, task: UUID) {
        let progress = download.progress
        let report: (Progress) -> Void = { [weak self] progress in
            let received = progress.completedUnitCount
            let total = progress.totalUnitCount
            Task { @MainActor in
                self?.ledger.update(id: task, bytesReceived: received, totalBytes: total)
            }
        }
        running[ObjectIdentifier(download)]?.progressObservations = [
            progress.observe(\.fractionCompleted, options: [.initial, .new]) { progress, _ in
                report(progress)
            },
            progress.observe(\.totalUnitCount, options: [.new]) { progress, _ in
                report(progress)
            },
        ]
    }

    /// A window that opened only for its file leaves nothing to show once the
    /// file is answered. The page closes then, never while it is already
    /// asleep (the browser ending must not drop its tabs).
    private func closeFileWindow(_ id: ObjectIdentifier) {
        guard let entry = running[id], entry.closesPage else { return }
        closeIfStillEmpty(entry.model)
    }

    /// The "only for its file" answer is given when the download starts, but
    /// a script can navigate the window to a real page while the file loads.
    /// The same test as at adoption is asked again, so that page is kept.
    private func closeIfStillEmpty(_ model: MiniBrowserModel?) {
        guard let model, let webView = model.webView,
              webView.url == nil, !webView.canGoBack else { return }
        model.host?.pageRequestedClose(model)
    }

    func downloadDidFinish(_ download: WKDownload) {
        let id = ObjectIdentifier(download)
        guard let done = running[id] else { return }
        running[id]?.progressObservations = []
        // The reader ended it, or it never had a file of its own: there is
        // nothing to hand to the save screen.
        guard !done.declined, !done.cancelled else {
            running.removeValue(forKey: id)
            removeTemporaryFolder(done.temporaryFolder)
            return
        }
        guard let task = done.taskID, let folder = done.temporaryFolder else {
            running.removeValue(forKey: id)
            return
        }
        let file = folder.appendingPathComponent(done.fileName)
        guard FileManager.default.fileExists(atPath: file.path) else {
            running.removeValue(forKey: id)
            removeTemporaryFolder(folder)
            ledger.fail(id: task, reason: String(localized: "browser.download.reason.save"))
            scheduleForget(task)
            return
        }
        ledger.chooseFolder(id: task)
        presentPicker(file: file, folder: folder, download: id, task: task)
        if done.closesPage { closeIfStillEmpty(done.model) }
    }

    /// The system's save screen for one file: the reader chooses the folder and
    /// may rename the file. The picker moves the file out of the temporary
    /// folder, so a save leaves nothing behind and a cancel only needs the
    /// temporary copy gone.
    private func presentPicker(file: URL, folder: URL, download: ObjectIdentifier, task: UUID) {
        guard let presenter = Self.topViewController() else {
            running.removeValue(forKey: download)
            removeTemporaryFolder(folder)
            ledger.fail(id: task, reason: String(localized: "browser.download.reason.save"))
            scheduleForget(task)
            return
        }
        let picker = UIDocumentPickerViewController(forExporting: [file], asCopy: false)
        picker.delegate = self
        picker.allowsMultipleSelection = false
        picker.shouldShowFileExtensions = true
        folderChoices[ObjectIdentifier(picker)] = FolderChoice(
            download: download,
            taskID: task,
            folder: folder,
            picker: picker
        )
        presenter.present(picker, animated: true)
    }

    /// The controller a system screen is presented from: the front of what is
    /// on display, so it also works from a sheet a page opened.
    static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        let window = scene?.windows.first(where: \.isKeyWindow) ?? scene?.windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController, !presented.isBeingDismissed {
            top = presented
        }
        return top
    }

    // MARK: - The save screen's answer

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let choice = folderChoices.removeValue(forKey: ObjectIdentifier(controller)) else { return }
        removeTemporaryFolder(choice.folder)
        running.removeValue(forKey: choice.download)
        // The picker moves the file, so its name at the destination is what
        // the reader now sees — unless they renamed it there.
        ledger.saved(id: choice.taskID, name: urls.first?.lastPathComponent)
        scheduleForget(choice.taskID)
    }

    /// Cancel in the save screen saves nothing, and nothing more is said.
    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        guard let choice = folderChoices.removeValue(forKey: ObjectIdentifier(controller)) else { return }
        removeTemporaryFolder(choice.folder)
        running.removeValue(forKey: choice.download)
        ledger.remove(id: choice.taskID)
        resultTasks[choice.taskID]?.cancel()
        resultTasks[choice.taskID] = nil
    }

    // MARK: - The capsule

    /// The capsule's cancel: the files shown stop arriving and nothing is
    /// saved. WebKit reports the cancellation; it is not a failed download.
    func cancel(taskIDs: [UUID]) {
        for task in taskIDs {
            ledger.remove(id: task)
            resultTasks[task]?.cancel()
            resultTasks[task] = nil
            guard let id = running.first(where: { $0.value.taskID == task })?.key,
                  let entry = running[id] else { continue }
            running[id]?.cancelled = true
            running[id]?.progressObservations = []
            entry.download.cancel { _ in }
        }
    }

    /// A result stays about five seconds, then goes, whichever page is up.
    private func scheduleForget(_ task: UUID) {
        resultTasks[task]?.cancel()
        resultTasks[task] = Task { @MainActor [weak self] in
            try? await Task.sleep(for: Self.resultDuration)
            guard !Task.isCancelled else { return }
            self?.ledger.remove(id: task)
            self?.resultTasks.removeValue(forKey: task)
        }
    }

    // MARK: - WebKit's endings

    func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
        let id = ObjectIdentifier(download)
        guard let failed = running.removeValue(forKey: id) else { return }
        removeTemporaryFolder(failed.temporaryFolder)
        // A download the reader declined or cancelled ends here as a
        // cancellation. That is not a failure to report.
        guard !failed.declined, !failed.cancelled else { return }
        if let task = failed.taskID {
            ledger.fail(id: task, reason: BrowserDownloadPolicy.failureReason(error))
            scheduleForget(task)
        }
        if failed.closesPage { closeIfStillEmpty(failed.model) }
    }
}
