import Foundation

/// Everything the share flow needs from the outside world: session check,
/// cached/fresh snapshots and the tab write. Mirrors Android `ShareRepository`.
@MainActor
protocol ShareSessioning {
    func isSignedIn() -> Bool
    func cachedSnapshot() -> ZenSnapshot?
    func lastSpaceId() -> String?
    func setLastSpaceId(_ id: String?)
    func refresh() async throws -> ZenSnapshot
    func addTab(
        url: URL,
        title: String,
        to spaceId: String,
        folderId: String?,
        kind: SaveKind
    ) async throws -> AddTabOutcome
    func errorText(_ error: Error) -> String
    func noSpacesErrorText() -> String
    func saveKind() -> SaveKind
}

struct LiveShareSession: ShareSessioning {
    nonisolated init() {}

    func isSignedIn() -> Bool { AccountStore.isSignedIn }

    func cachedSnapshot() -> ZenSnapshot? { SpacesSyncService.cachedSnapshot() }

    func lastSpaceId() -> String? {
        AppGroup.defaults.string(forKey: "lastSpaceId")
    }

    func setLastSpaceId(_ id: String?) {
        AppGroup.defaults.set(id, forKey: "lastSpaceId")
    }

    func refresh() async throws -> ZenSnapshot {
        try await SpacesSyncService.refresh()
    }

    func addTab(
        url: URL,
        title: String,
        to spaceId: String,
        folderId: String?,
        kind: SaveKind
    ) async throws -> AddTabOutcome {
        try await SpacesSyncService.addTab(
            url: url,
            title: title,
            to: spaceId,
            folderId: folderId,
            kind: kind
        )
    }

    func errorText(_ error: Error) -> String { error.zenUserMessage }

    func noSpacesErrorText() -> String {
        String(localized: "share.no_spaces")
    }

    /// AppGroup-first, standard fallback, `.pinned` default (mirrors
    /// `SettingsModel.loadSaveKind`; the key matches `PreferenceKeys.saveKind`
    /// in the app target, which the extension does not compile).
    func saveKind() -> SaveKind {
        let key = "save_kind"
        if let raw = AppGroup.defaults.string(forKey: key), let value = SaveKind(rawValue: raw) {
            return value
        }
        if let raw = UserDefaults.standard.string(forKey: key), let value = SaveKind(rawValue: raw) {
            return value
        }
        return .pinned
    }
}

/// Shared items can smuggle `file://` or custom-scheme URLs into the sync
/// write path; only http(s) is ever pinned, mirroring Android `ShareActivity`.
enum ShareLink {
    static func httpURL(from url: URL) -> URL? {
        let scheme = url.scheme?.lowercased()
        return (scheme == "http" || scheme == "https") ? url : nil
    }

    static func httpURL(from item: NSSecureCoding?) -> URL? {
        if let url = item as? URL { return httpURL(from: url) }
        if let text = item as? String { return link(in: text) }
        if let text = item as? NSString { return link(in: text as String) }
        if let data = item as? Data, let url = URL(dataRepresentation: data, relativeTo: nil) {
            return httpURL(from: url)
        }
        return nil
    }

    /// Android `ShareActivity.sharedLink` checks the whole text first and
    /// then each word, so a share like "look at this https://…" keeps its
    /// link. Only a text without inner whitespace can be one address.
    private static func link(in text: String) -> URL? {
        if !text.contains(where: { $0.isWhitespace }), let url = candidate(text) {
            return url
        }
        for word in text.split(whereSeparator: { $0.isWhitespace }) {
            if let url = candidate(String(word)) { return url }
        }
        return nil
    }

    /// One candidate word or the whole text, without the marks the sentence
    /// around it adds. Only an http(s) address with a host is a link.
    private static func candidate(_ text: String) -> URL? {
        let bare = trimmingSurroundingMarks(text)
        guard !bare.isEmpty, let url = URL(string: bare), let http = httpURL(from: url),
              http.host?.isEmpty == false else { return nil }
        return http
    }

    /// Marks a sentence puts before a link; they never belong to its address.
    private static let leadingMarks: Set<Character> = ["\"", "'", "“", "”", "‘", "’", "(", "[", "{", "<"]

    /// Marks a sentence puts right after a link.
    private static let sentenceMarks: Set<Character> = [".", ",", ";", ":", "!", "?", "…"]
    private static let quoteMarks: Set<Character> = ["\"", "'", "“", "”", "‘", "’"]
    /// Closing bracket → the opening bracket it has to pair with.
    private static let bracketPairs: [Character: Character] = [")": "(", "]": "[", "}": "{", ">": "<"]

    /// Android `trimSurroundingMarks`: drops the leading quotes and brackets,
    /// then the sentence marks that close the sentence around the link, then
    /// the quotes and unbalanced closing brackets it is wrapped in. A mark
    /// inside that wrapper stays, since an address may end in one: a quoted
    /// `…?q=wow!` keeps its `!`. A closing bracket only goes while it is
    /// unbalanced, so a linked `Function_(mathematics)` keeps its own `)`.
    private static func trimmingSurroundingMarks(_ text: String) -> String {
        var characters = Array(
            text.trimmingCharacters(in: .whitespacesAndNewlines)
                .drop(while: { leadingMarks.contains($0) })
        )
        while let last = characters.last, sentenceMarks.contains(last) {
            characters.removeLast()
        }
        while let last = characters.last {
            if quoteMarks.contains(last) {
                characters.removeLast()
                continue
            }
            if let opener = bracketPairs[last] {
                let opens = characters.filter { $0 == opener }.count
                let closes = characters.filter { $0 == last }.count
                guard opens < closes else { break }
                characters.removeLast()
                continue
            }
            break
        }
        return String(characters)
    }

    static func headlineTitle(pageTitle: String, url: URL?) -> String {
        PageTitle.headline(pageTitle, url: url)
    }
}

/// Bootstrap / refresh / save state machine for the share sheet. Port of
/// Android `ShareViewModel`. The view owns item-provider loading and chrome.
@MainActor
@Observable
final class ShareModel {
    enum Phase: Equatable {
        case loading, pick, saving, saved, failed, signedOut
    }

    var url: URL?
    var pageTitle: String
    var spaces: [ZenSpace] = []
    var destination = PinDestination(spaceId: "")
    var phase: Phase = .loading
    var error: String?
    /// Pinned or normal for *this* share. Starts at the persisted setting and
    /// is only changed by the user in this sheet; it is never written back.
    var saveKind: SaveKind = .pinned
    var savedAsPinnedFallback = false
    /// The synced browser's normal-tabs capability. Normal is impossible
    /// unless this is `.enabled` (SPEC §7), so the per-share choice is hidden
    /// until the browser proves it can write normal records.
    private(set) var normalTabsCapability: NormalTabsCapability = .absent
    /// Set when the auto-close delay elapses after a successful save.
    var didFinish = false

    var allowsNormalTabs: Bool { normalTabsCapability.allowsNormalTabs }

    var hideFolders: Bool { saveKind == .normal }

    var selectedSpace: ZenSpace? {
        spaces.first(where: { $0.id == destination.spaceId })
    }

    var canSave: Bool {
        !destination.spaceId.isEmpty && url != nil && phase != .saving
    }

    var savedDestinationName: String {
        guard let space = selectedSpace else {
            return String(localized: "share.workspace")
        }
        return PinDestinationModel.displayName(destination: destination, in: space)
    }

    var headlineTitle: String {
        ShareLink.headlineTitle(pageTitle: pageTitle, url: url)
    }

    var onFinished: (() -> Void)?

    private let session: ShareSessioning
    private let sleep: (Duration) async -> Void
    private let titleResolver: @Sendable (String, URL) async -> String?
    /// The saved setting, applied until the user overrides this share.
    private let preferredSaveKind: SaveKind
    @ObservationIgnored private var hasChosenSaveKind = false
    @ObservationIgnored private var resolveTask: Task<Void, Never>?

    init(
        session: ShareSessioning,
        pageTitle: String = "",
        url: URL? = nil,
        sleep: @escaping (Duration) async -> Void = { try? await Task.sleep(for: $0) },
        titleResolver: @escaping @Sendable (String, URL) async -> String? = { _, _ in nil }
    ) {
        let cachedCapability = session.cachedSnapshot()?.normalTabsCapability ?? .absent
        self.session = session
        self.pageTitle = pageTitle
        self.url = url
        self.sleep = sleep
        self.titleResolver = titleResolver
        self.preferredSaveKind = session.saveKind()
        self.normalTabsCapability = cachedCapability
        self.saveKind = cachedCapability.allowsNormalTabs ? preferredSaveKind : .pinned
    }

    /// Replaces a missing or URL-shaped name with the page's real title.
    /// Safe to call more than once; a save waits for the in-flight lookup.
    func resolveSharedTitle() async {
        guard let url else { return }
        guard PageTitle.usable(pageTitle, url: url) == nil else { return }
        if let resolveTask {
            await resolveTask.value
            return
        }
        let task = Task { await resolveSharedTitleNow() }
        resolveTask = task
        await task.value
    }

    private func resolveSharedTitleNow() async {
        guard let url, PageTitle.usable(pageTitle, url: url) == nil else { return }
        guard let resolved = await titleResolver(pageTitle, url) else { return }
        guard PageTitle.usable(pageTitle, url: url) == nil else { return }
        pageTitle = resolved
    }

    func selectDestination(_ chosen: PinDestination) {
        destination = hideFolders
            ? PinDestination(spaceId: chosen.spaceId)
            : chosen
    }

    /// Per-share override from the sheet's save-kind control. Deliberately
    /// does not touch the persisted setting: the next share starts at the
    /// saved default again.
    func selectSaveKind(_ kind: SaveKind) {
        guard kind == .pinned || allowsNormalTabs else { return }
        hasChosenSaveKind = true
        guard kind != saveKind else { return }
        saveKind = kind
        // A normal tab always lands at the space root, so drop a folder that
        // belonged to the pinned choice.
        if kind == .normal {
            destination = PinDestination(spaceId: destination.spaceId)
        }
    }

    /// Applies the synced capability without clobbering a choice the user
    /// already made in this sheet.
    private func applyCapability(_ capability: NormalTabsCapability) {
        normalTabsCapability = capability
        guard capability.allowsNormalTabs else {
            saveKind = .pinned
            return
        }
        if !hasChosenSaveKind {
            saveKind = preferredSaveKind
        }
    }

    func bootstrap() async {
        guard session.isSignedIn() else {
            phase = .signedOut
            return
        }
        if let cached = session.cachedSnapshot() {
            applyCapability(cached.normalTabsCapability)
        }

        if let cached = session.cachedSnapshot(), !cached.spaces.isEmpty {
            spaces = cached.spaces
            destination = lastDestination(fallback: cached.spaces)
            phase = .pick
        }

        await refreshSpaces()
    }

    func refreshSpaces() async {
        do {
            let fresh = try await session.refresh()
            guard !fresh.spaces.isEmpty else {
                // Nothing cached and nothing synced: say so instead of
                // spinning forever.
                if spaces.isEmpty {
                    error = session.noSpacesErrorText()
                    phase = .failed
                }
                return
            }
            spaces = fresh.spaces
            applyCapability(fresh.normalTabsCapability)
            if !fresh.spaces.contains(where: { $0.id == destination.spaceId }) {
                destination = lastDestination(fallback: fresh.spaces)
            } else if hideFolders {
                destination = PinDestination(spaceId: destination.spaceId)
            } else if let folderId = destination.folderId,
                      let currentSpace = selectedSpace,
                      PinDestinationModel.folderName(folderId: folderId, in: currentSpace) == nil {
                destination = PinDestination(spaceId: destination.spaceId)
            }
            // A refresh that lands mid-save must not re-enable the button,
            // and one that lands after a failed write must not hide the error.
            if phase != .saved && phase != .saving && phase != .failed {
                phase = .pick
            }
        } catch {
            if spaces.isEmpty {
                self.error = session.errorText(error)
                phase = session.isSignedIn() ? .failed : .signedOut
            }
        }
    }

    func save() async {
        guard let url, phase != .saving, phase != .saved else { return }
        if spaces.isEmpty {
            error = session.noSpacesErrorText()
            phase = .failed
            return
        }
        guard let selected = selectedSpace else { return }
        phase = .saving
        // Capture the user's choice before the title lookup yields: a refresh
        // landing during the await must not change what this tap writes.
        let kind = saveKind
        let folderId = kind == .normal ? nil : destination.folderId
        await resolveSharedTitle()
        do {
            let outcome = try await session.addTab(
                url: url,
                title: headlineTitle,
                to: selected.id,
                folderId: folderId,
                kind: kind
            )
            savedAsPinnedFallback = outcome.fellBackToPinned
            session.setLastSpaceId(selected.id)
            phase = .saved
            await sleep(.milliseconds(outcome.fellBackToPinned ? 2400 : 480))
            didFinish = true
            onFinished?()
        } catch {
            self.error = session.errorText(error)
            phase = .failed
        }
    }

    /// Only the space is remembered across shares — never the folder.
    private func lastDestination(fallback spaces: [ZenSpace]) -> PinDestination {
        if let lastSpaceId = session.lastSpaceId(),
           spaces.contains(where: { $0.id == lastSpaceId }) {
            return PinDestination(spaceId: lastSpaceId)
        }
        if let first = spaces.first {
            return PinDestination(spaceId: first.id)
        }
        return PinDestination(spaceId: "")
    }
}
