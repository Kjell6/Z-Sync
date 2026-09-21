import Foundation
import Observation
import SwiftUI
import WebKit

/// State owner for the mini-browser: web-view progress/URL state fed by KVO,
/// the address field text, URL/search resolution and the pin-save banner state
/// machine. Replaces the former `MiniBrowserState` + view state in
/// `MiniBrowserView`.
@MainActor
@Observable
final class MiniBrowserModel {
    // MARK: Web-view state (updated by `MiniBrowserRepresentable`'s KVO)
    var currentURL: URL?
    var pageTitle: String = ""
    var isLoading: Bool = false
    var estimatedProgress: Double = 0.0
    var canGoBack: Bool = false
    var canGoForward: Bool = false
    var themeColor: Color?

    @ObservationIgnored weak var webView: WKWebView?

    // MARK: Address field
    var addressText: String = ""

    // MARK: Pin banner
    var showPinBanner = false
    var pinnedDestination = PinDestination(spaceId: "")
    var hasPendingPinSave = false
    /// How the pending save will be written (pinned vs normal). Captured when
    /// the banner is triggered and kept honest against the capability.
    private(set) var pinSaveKind: SaveKind = .pinned
    /// Short-lived refusal message when a pin cannot start (no spaces to
    /// attach the page to). Rendered in the same bottom toast as the banner.
    var pinNotice: String?
    /// Set once the banner's destination picker has been opened. While a
    /// native context menu is presented, iOS 26 renders `glassEffect` as an
    /// opaque white slab and it stays broken through the closing animation,
    /// so the banner drops to its plain brand fill for the rest of its run.
    private(set) var pinBannerGlassSuppressed = false

    @ObservationIgnored private var pinBannerDismissTask: Task<Void, Never>?
    @ObservationIgnored private var pinNoticeDismissTask: Task<Void, Never>?
    @ObservationIgnored private(set) var pinSaveTask: Task<Void, Never>?

    private let initialURL: URL?
    private let pinWriter: PinWriting
    private let urlOpener: URLOpening
    private let searchEngine: SearchEngineProviding
    private let haptics: HapticsPlaying
    private let preferences: PreferencesStoring

    init(
        initialURL: URL?,
        pinWriter: PinWriting = AppServices.pinWriter,
        urlOpener: URLOpening = AppServices.urlOpener,
        searchEngine: SearchEngineProviding = AppServices.searchEngine,
        haptics: HapticsPlaying = AppServices.haptics,
        preferences: PreferencesStoring = AppServices.preferences
    ) {
        self.initialURL = initialURL
        self.pinWriter = pinWriter
        self.urlOpener = urlOpener
        self.searchEngine = searchEngine
        self.haptics = haptics
        self.preferences = preferences
    }

    /// The persisted "save shared tabs as" choice. AppGroup first, standard
    /// fallback; unknown values mean `.pinned`.
    var saveKind: SaveKind {
        let key = PreferenceKeys.saveKind
        if let raw = preferences.string(key, scope: .appGroup), let value = SaveKind(rawValue: raw) {
            return value
        }
        if let raw = preferences.string(key, scope: .standard), let value = SaveKind(rawValue: raw) {
            return value
        }
        return .pinned
    }

    /// A new tab opens with the search field focused instead of loading a page.
    var opensWithSearch: Bool { initialURL == nil }

    // MARK: - URL resolution

    /// Precedence: live page URL → the web view's URL → the initial URL →
    /// whatever the address field currently resolves to.
    var effectiveURL: URL? {
        if let current = currentURL {
            return current
        }
        if let webURL = webView?.url {
            return webURL
        }
        if let initialURL {
            return initialURL
        }
        return BrowserInput.resolve(addressText, engine: searchEngine.current)
    }

    // MARK: - Loading

    func loadInitial() {
        if let initialURL {
            load(url: initialURL)
            addressText = BrowserInput.displayText(for: initialURL)
        }
    }

    func load(url: URL) {
        webView?.load(URLRequest(url: url))
    }

    func reload() {
        webView?.reload()
    }

    func stopLoading() {
        webView?.stopLoading()
    }

    func goBack() {
        webView?.goBack()
    }

    func goForward() {
        webView?.goForward()
    }

    func openCurrentInExternalBrowser() {
        if let url = effectiveURL {
            urlOpener.open(url)
        }
    }

    // MARK: - Address field intents

    func submitAddress() {
        guard let url = BrowserInput.resolve(addressText, engine: searchEngine.current) else { return }
        load(url: url)
    }

    func clearAddressText() {
        addressText = ""
    }

    func currentURLDidChange(_ url: URL?, isAddressFocused: Bool) {
        if let url {
            if !isAddressFocused {
                addressText = BrowserInput.displayText(for: url)
            }
            showPinBanner = false
        }
    }

    func addressFocusDidChange(_ focused: Bool) {
        if focused {
            if let url = effectiveURL {
                addressText = url.absoluteString
            }
        } else {
            if let url = effectiveURL {
                addressText = BrowserInput.displayText(for: url)
            }
        }
    }

    // MARK: - Pin save state machine

    func triggerPinBanner(spaces: [ZenSpace], fallbackSpace: ZenSpace) {
        guard effectiveURL != nil else { return }
        guard !spaces.isEmpty else {
            // There is no attached destination, so refuse instead of writing
            // to an unattached workspace id (fallbackSpace is not in sync).
            refusePinWithoutSpaces()
            return
        }
        pinNoticeDismissTask?.cancel()
        pinNotice = nil
        pinSaveKind = saveKind
        pinBannerGlassSuppressed = false
        if pinnedDestination.spaceId.isEmpty
            || !spaces.contains(where: { $0.id == pinnedDestination.spaceId }) {
            pinnedDestination = PinDestination(
                spaceId: spaces.first(where: { $0.id == fallbackSpace.id })?.id
                    ?? spaces.first?.id
                    ?? fallbackSpace.id
            )
        }
        // A normal save ignores folders; never leave a stale folder selected.
        if pinSaveKind == .normal {
            pinnedDestination = PinDestination(spaceId: pinnedDestination.spaceId)
        }
        hasPendingPinSave = true

        haptics.pinSucceeded()

        withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
            showPinBanner = true
        }

        // Auto-dismiss banner after initial 5.0s and then commit save
        restartBannerDismissTimer(after: 5.0)
    }

    /// No spaces to pin into: keep the write path off and tell the user with
    /// the same bottom-toast language as the pin banner.
    private func refusePinWithoutSpaces() {
        hasPendingPinSave = false
        withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
            showPinBanner = false
        }
        showPinNotice(String(localized: "browser.pin_no_space"))
    }

    private func showPinNotice(_ text: String, duration: TimeInterval = 4.0) {
        pinNotice = text
        pinNoticeDismissTask?.cancel()
        pinNoticeDismissTask = Task {
            let nanoseconds = UInt64(duration * 1_000_000_000)
            try? await Task.sleep(nanoseconds: nanoseconds)
            guard !Task.isCancelled else { return }
            withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                pinNotice = nil
            }
        }
    }

    func selectPinDestination(_ destination: PinDestination) {
        pinnedDestination = pinSaveKind == .normal
            ? PinDestination(spaceId: destination.spaceId)
            : destination
        hasPendingPinSave = true

        haptics.selectionChanged()

        // 1.0s confirmation after user explicitly selected a destination
        restartBannerDismissTimer(after: 1.0)
    }

    /// Keep the banner alive while the picker is open.
    func pinMenuWillOpen() {
        pinBannerDismissTask?.cancel()
        pinBannerGlassSuppressed = true
    }

    func pinMenuDidDismiss() {
        restartBannerDismissTimer(after: 1.5)
    }

    func commitPendingPinSave() {
        guard hasPendingPinSave,
              let url = effectiveURL else {
            return
        }
        hasPendingPinSave = false
        let target = pinnedDestination
        let kind = pinSaveKind
        let titleToSave = pageTitle.isEmpty ? (url.host ?? url.absoluteString) : pageTitle

        pinSaveTask = Task(priority: .userInitiated) {
            // Decoupled task with userInitiated priority guarantees completion even if view/sheet is dismissed.
            // A failed write surfaces through the app's existing error handling.
            if let outcome = try? await pinWriter.addTab(
                url: url,
                title: titleToSave,
                to: target.spaceId,
                folderId: kind == .normal ? nil : target.folderId,
                kind: kind
            ), outcome.fellBackToPinned {
                showPinNotice(String(localized: "share.saved.fallback_pinned"), duration: 6.0)
            }
        }
    }

    /// Test/await seam for the decoupled pin write; production never waits.
    func waitForPinSave() async {
        await pinSaveTask?.value
    }

    private func restartBannerDismissTimer(after seconds: Double = 5.0) {
        pinBannerDismissTask?.cancel()
        pinBannerDismissTask = Task {
            let nanoseconds = UInt64(seconds * 1_000_000_000)
            try? await Task.sleep(nanoseconds: nanoseconds)
            guard !Task.isCancelled else { return }
            commitPendingPinSave()
            withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                showPinBanner = false
            }
        }
    }

    // MARK: - Theme color

    func updateThemeColor(from string: String?, nativeColor: UIColor?) {
        if let native = nativeColor {
            self.themeColor = Color(uiColor: native)
            return
        }
        guard let string = string?.trimmingCharacters(in: .whitespacesAndNewlines), !string.isEmpty else {
            self.themeColor = nil
            return
        }
        if string.starts(with: "#") {
            self.themeColor = Color(hex: string)
        } else if string.starts(with: "rgb") {
            self.themeColor = Color(cssRGB: string)
        } else {
            self.themeColor = nil
        }
    }
}
