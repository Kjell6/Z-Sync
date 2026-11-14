import CoreLocation
import XCTest

@testable import ZenCompanion

@MainActor
final class BrowserPageRequestsTests: XCTestCase {
    private func makeStore() -> SitePermissionStore {
        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathComponent("permissions.json")
        addTeardownBlock { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        return SitePermissionStore(fileURL: file)
    }

    // MARK: - Site permissions

    func testAnUnknownSiteIsAsked() {
        XCTAssertEqual(makeStore().decision(for: [.camera], host: "example.com"), .ask)
    }

    func testAnAnswerStaysForThatSiteOnly() {
        let store = makeStore()
        store.set(.allow, for: [.camera], host: "example.com")
        XCTAssertEqual(store.decision(for: [.camera], host: "EXAMPLE.com"), .grant)
        XCTAssertEqual(store.decision(for: [.camera], host: "other.com"), .ask)
        XCTAssertEqual(store.decision(for: [.microphone], host: "example.com"), .ask)
    }

    func testOneBlockedKindBlocksTheWholeRequest() {
        let store = makeStore()
        store.set(.allow, for: [.camera], host: "example.com")
        store.set(.block, for: [.microphone], host: "example.com")
        XCTAssertEqual(store.decision(for: [.camera, .microphone], host: "example.com"), .deny)
    }

    func testEveryKindMustBeAllowedToSkipTheQuestion() {
        let store = makeStore()
        store.set(.allow, for: [.camera], host: "example.com")
        XCTAssertEqual(store.decision(for: [.camera, .microphone], host: "example.com"), .ask)
    }

    func testAskClearsARememberedAnswer() {
        let store = makeStore()
        store.set(.block, for: [.location], host: "example.com")
        store.set(nil, for: [.location], host: "example.com")
        XCTAssertNil(store.choice(for: .location, host: "example.com"))
    }

    func testAnswersSurviveARelaunchAndGoWithRemoveAll() {
        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathComponent("permissions.json")
        let store = SitePermissionStore(fileURL: file)
        store.set(.allow, for: [.location], host: "example.com")
        XCTAssertEqual(SitePermissionStore(fileURL: file).choice(for: .location, host: "example.com"), .allow)
        store.removeAll()
        XCTAssertNil(SitePermissionStore(fileURL: file).choice(for: .location, host: "example.com"))
    }

    func testWWWFoldsIntoTheBareHost() {
        let store = makeStore()
        store.set(.allow, for: [.location], host: "www.google.com")
        XCTAssertEqual(store.decision(for: [.location], host: "google.com"), .grant)
        XCTAssertEqual(store.decision(for: [.location], host: "WWW.GOOGLE.COM"), .grant)
        // The rest of the host still counts, so Maps keeps its own answer.
        store.set(.block, for: [.location], host: "maps.google.com")
        XCTAssertEqual(store.decision(for: [.location], host: "google.com"), .grant)
        XCTAssertEqual(store.decision(for: [.location], host: "maps.google.com"), .deny)
    }

    func testAnAnswerSavedUnderWWWStillCounts() throws {
        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathComponent("permissions.json")
        addTeardownBlock { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        try FileManager.default.createDirectory(
            at: file.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        let saved = ["www.google.com|location": SitePermissionChoice.block]
        try JSONEncoder().encode(saved).write(to: file)

        let store = SitePermissionStore(fileURL: file)
        XCTAssertEqual(store.decision(for: [.location], host: "google.com"), .deny)
        XCTAssertEqual(store.decision(for: [.location], host: "www.google.com"), .deny)
        // Moved onto the bare host, so a later change replaces it instead of
        // leaving the old key to answer for it.
        XCTAssertEqual(store.choices.keys.sorted(), ["google.com|location"])
    }

    func testAnUnansweredQuestionIsANoUntilTheAppRestarts() {
        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathComponent("permissions.json")
        addTeardownBlock { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        let store = SitePermissionStore(fileURL: file)
        let model = MiniBrowserModel(initialURL: nil, sitePermissions: store)
        var answers: [Bool] = []
        model.requestPermission([.location], host: "www.gone.example") { answers.append($0) }
        model.dismissDialog(model.dialogs[0])
        XCTAssertEqual(answers, [false])

        // The same site asking again hears no without a new question, `www.`
        // or not, and the next launch asks again because nothing was saved.
        XCTAssertEqual(store.decision(for: [.location], host: "gone.example"), .deny)
        model.requestPermission([.location], host: "gone.example") { answers.append($0) }
        XCTAssertEqual(answers, [false, false])
        XCTAssertTrue(model.dialogs.isEmpty)
        XCTAssertNil(SitePermissionStore(fileURL: file).choice(for: .location, host: "gone.example"))
    }

    func testARealAnswerReplacesTheUnansweredNo() {
        let store = makeStore()
        let model = MiniBrowserModel(initialURL: nil, sitePermissions: store)
        model.requestPermission([.location], host: "gone.example") { _ in }
        model.dismissDialog(model.dialogs[0])

        // A change in Site Settings lets the question come back.
        store.set(nil, for: [.location], host: "gone.example")
        XCTAssertEqual(store.decision(for: [.location], host: "gone.example"), .ask)
        store.set(.allow, for: [.location], host: "gone.example")
        XCTAssertEqual(store.decision(for: [.location], host: "gone.example"), .grant)
    }

    func testTheSystemExplanationIsShownOncePerSite() {
        let store = makeStore()
        XCTAssertTrue(store.shouldExplainSystemAccess(host: "google.com"))
        XCTAssertFalse(store.shouldExplainSystemAccess(host: "www.google.com"))
        // A different site, and the next launch, explain again.
        XCTAssertTrue(store.shouldExplainSystemAccess(host: "maps.google.com"))
        store.removeAll()
        XCTAssertTrue(store.shouldExplainSystemAccess(host: "google.com"))
    }

    func testPermissionFileStaysOutOfBackups() throws {
        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathComponent("permissions.json")
        addTeardownBlock { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        SitePermissionStore(fileURL: file).set(.allow, for: [.camera], host: "example.com")
        XCTAssertEqual(try file.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
    }

    // MARK: - The order of the questions

    func testAskingShowsTheBrowsersQuestionAndWaits() {
        let store = makeStore()
        let model = MiniBrowserModel(initialURL: nil, sitePermissions: store)
        var answers: [Bool] = []
        model.requestPermission([.camera], host: "ask.example") { answers.append($0) }
        XCTAssertTrue(answers.isEmpty, "Nothing reaches the system before the reader answered")
        guard case .permission(let host, let kinds, let answer)? = model.dialogs.first?.kind else {
            return XCTFail("Expected the browser's question")
        }
        XCTAssertEqual(host, "ask.example")
        XCTAssertEqual(kinds, [.camera])
        answer.send(false)
        XCTAssertEqual(answers, [false])
        XCTAssertEqual(store.choice(for: .camera, host: "ask.example"), .block)
    }

    func testAQuestionThatGoesAwayRefusesWithoutRemembering() {
        let store = makeStore()
        var answers: [Bool] = []
        let model = MiniBrowserModel(initialURL: nil, sitePermissions: store)
        model.requestPermission([.camera], host: "gone.example") { answers.append($0) }
        model.dismissDialog(model.dialogs[0])
        XCTAssertEqual(answers, [false])
        XCTAssertNil(store.choice(for: .camera, host: "gone.example"))
    }

    func testABlockedSiteHearsNoWithoutAQuestion() {
        let store = makeStore()
        let model = MiniBrowserModel(initialURL: nil, sitePermissions: store)
        store.set(.block, for: [.location], host: "blocked.example")
        var answers: [Bool] = []
        model.requestPermission([.location], host: "blocked.example") { answers.append($0) }
        XCTAssertEqual(answers, [false])
        XCTAssertTrue(model.dialogs.isEmpty)
    }

    func testADroppedDialogStillAnswersThePage() {
        var answers: [Bool] = []
        do {
            let answer = DialogAnswer<Bool>(fallback: false) { answers.append($0) }
            _ = answer
        }
        XCTAssertEqual(answers, [false])
    }

    func testAnAnswerIsGivenOnce() {
        var answers: [Bool] = []
        do {
            let answer = DialogAnswer<Bool>(fallback: false) { answers.append($0) }
            answer.send(true)
            answer.send(false)
        }
        XCTAssertEqual(answers, [true])
    }

    // MARK: - Dialog titles

    func testDownloadQuestionNamesTheFileAndTheSite() {
        var answers: [Bool] = []
        let answer = DialogAnswer<Bool>(fallback: false) { answers.append($0) }
        let dialog = BrowserDialog(kind: .download(
            host: "example.com",
            fileName: "report.pdf",
            answer
        ))

        XCTAssertEqual(dialog.title, "Download report.pdf?")
        XCTAssertEqual(BrowserDialog.downloadMessage(host: "example.com"), "From example.com")
        XCTAssertEqual(BrowserDialog.downloadMessage(host: ""), "From This page")
        answer.send(true)
        XCTAssertEqual(answers, [true])
    }

    func testTheSameFileIsAskedOncePerPage() {
        let page = MiniBrowserModel(initialURL: nil)
        let other = MiniBrowserModel(initialURL: nil)
        let file = URL(string: "https://example.com/report.pdf")!
        let waiting = [BrowserDownloadPolicy.Question(page: ObjectIdentifier(page), address: file)]

        XCTAssertTrue(BrowserDownloadPolicy.isDuplicate(
            address: file,
            page: ObjectIdentifier(page),
            waiting: waiting
        ))
        XCTAssertFalse(BrowserDownloadPolicy.isDuplicate(
            address: file,
            page: ObjectIdentifier(other),
            waiting: waiting
        ))
        XCTAssertFalse(BrowserDownloadPolicy.isDuplicate(
            address: URL(string: "https://example.com/notes.pdf")!,
            page: ObjectIdentifier(page),
            waiting: waiting
        ))
        XCTAssertFalse(BrowserDownloadPolicy.isDuplicate(
            address: nil,
            page: ObjectIdentifier(page),
            waiting: waiting
        ))
        XCTAssertFalse(BrowserDownloadPolicy.isDuplicate(
            address: file,
            page: ObjectIdentifier(page),
            waiting: []
        ))
    }

    func testPermissionTitlesNameTheSite() {
        XCTAssertEqual(
            BrowserDialog.permissionTitle(host: "example.com", kinds: [.camera, .microphone]),
            "example.com wants to use your camera and microphone"
        )
        XCTAssertEqual(
            BrowserDialog.permissionTitle(host: "example.com", kinds: [.location]),
            "example.com wants to know your location"
        )
    }

    // MARK: - Location

    func testLocationIsOnlyForSecureOrigins() {
        XCTAssertTrue(BrowserGeolocation.isSecureOrigin(protocol: "https", host: "example.com"))
        XCTAssertTrue(BrowserGeolocation.isSecureOrigin(protocol: "http", host: "localhost"))
        XCTAssertFalse(BrowserGeolocation.isSecureOrigin(protocol: "http", host: "example.com"))
    }

    func testLocationReplyLeavesUnknownValuesEmpty() throws {
        let location = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: 52.5, longitude: 13.4),
            altitude: 0,
            horizontalAccuracy: 20,
            verticalAccuracy: -1,
            course: -1,
            speed: -1,
            timestamp: Date(timeIntervalSince1970: 10)
        )
        let reply = BrowserGeolocation.reply(for: location)
        let coords = try XCTUnwrap(reply["coords"] as? [String: Any])
        XCTAssertEqual(coords["latitude"] as? Double, 52.5)
        XCTAssertEqual(coords["accuracy"] as? Double, 20)
        XCTAssertTrue(coords["altitude"] is NSNull)
        XCTAssertTrue(coords["heading"] is NSNull)
        XCTAssertTrue(coords["speed"] is NSNull)
        XCTAssertEqual(reply["timestamp"] as? Double, 10_000)
    }

    // MARK: - Downloads

    func testWhatBecomesAFile() {
        typealias Policy = BrowserDownloadPolicy
        XCTAssertTrue(Policy.shouldDownload(isMainFrame: true, canShowMIMEType: false, contentDisposition: nil))
        XCTAssertTrue(Policy.shouldDownload(isMainFrame: true, canShowMIMEType: true, contentDisposition: " Attachment; filename=a.pdf"))
        XCTAssertFalse(Policy.shouldDownload(isMainFrame: true, canShowMIMEType: true, contentDisposition: "inline"))
        XCTAssertFalse(Policy.shouldDownload(isMainFrame: true, canShowMIMEType: true, contentDisposition: nil))
        XCTAssertFalse(Policy.shouldDownload(isMainFrame: false, canShowMIMEType: false, contentDisposition: "attachment"))
    }

    func testAServerNameStaysInsideTheFolder() {
        XCTAssertEqual(BrowserDownloadPolicy.safeFileName("../../etc/passwd"), "_.._etc_passwd")
        XCTAssertEqual(BrowserDownloadPolicy.safeFileName("..."), "download")
        XCTAssertEqual(BrowserDownloadPolicy.safeFileName("report.pdf"), "report.pdf")
        XCTAssertEqual(BrowserDownloadPolicy.safeFileName("a\nb\u{7}.pdf"), "a_b_.pdf")
        XCTAssertEqual(BrowserDownloadPolicy.safeFileName("Family 👨‍👩‍👧.pdf"), "Family 👨‍👩‍👧.pdf")
    }

    func testATakenNameIsNumbered() {
        let taken: Set<String> = ["report.pdf", "report 2.pdf", "notes"]
        let unique = { BrowserDownloadPolicy.uniqueFileName($0) { taken.contains($0) } }
        XCTAssertEqual(unique("free.pdf"), "free.pdf")
        XCTAssertEqual(unique("report.pdf"), "report 3.pdf")
        XCTAssertEqual(unique("notes"), "notes 2")
    }
}
