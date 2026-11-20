import SwiftUI

/// Whether the short onboarding still has to run. It is for people who just
/// installed the app: a reader who updated from an earlier version already
/// knows it, and the What's New sheet covers what changed for them.
@MainActor
struct OnboardingStore {
    private let preferences: PreferencesStoring

    init(preferences: PreferencesStoring = AppServices.preferences) {
        self.preferences = preferences
    }

    /// True on a fresh install until the onboarding was finished or skipped.
    /// Without a recorded first launch nothing is known, so it stays quiet.
    var shouldPresent: Bool {
        #if DEBUG
        // `-showOnboarding` at launch shows it again on an installed app.
        if ProcessInfo.processInfo.arguments.contains("-showOnboarding") { return true }
        #endif
        guard !preferences.bool(PreferenceKeys.didCompleteOnboarding, scope: .standard),
              let firstLaunch = preferences.string(PreferenceKeys.firstLaunchVersion, scope: .standard)
        else { return false }
        return firstLaunch != PreferenceKeys.updatedFromEarlierVersion
    }

    /// Call when the reader leaves the onboarding, by Skip or Get started. An
    /// app killed halfway shows it again on the next launch.
    func markCompleted() {
        preferences.setBool(true, PreferenceKeys.didCompleteOnboarding, scope: .standard)
    }
}

/// Three pages, one headline each: the spaces, saving from other apps, and the
/// browser. Skip at the top, the dots and one button at the bottom.
struct OnboardingView: View {
    let onFinish: () -> Void

    @Environment(\.colorScheme) private var scheme
    @State private var page = 0

    private enum Page: Int, CaseIterable {
        case spaces, share, browser

        var title: LocalizedStringKey {
            switch self {
            case .spaces: "onboarding.spaces.title"
            case .share: "onboarding.share.title"
            case .browser: "onboarding.browser.title"
            }
        }
    }

    private var isLastPage: Bool { page == Page.allCases.count - 1 }

    var body: some View {
        VStack(spacing: 0) {
            pages
                // Skip floats over the pages, so a card's shadow can reach up
                // under it instead of being cut at the pages' top edge.
                .overlay(alignment: .topTrailing) { skipButton }

            dots
                .padding(.top, 22)
                .padding(.bottom, 28)

            continueButton
        }
        // The same centred column as the spaces screen on a wide display.
        .frame(maxWidth: SpacesLayout.maxWidth)
        .frame(maxWidth: .infinity)
        .background(backdrop.ignoresSafeArea())
    }

    private static let skipRowHeight: CGFloat = 44

    private var skipButton: some View {
                Button(action: onFinish) {
                    HStack(spacing: 2) {
                        Text("onboarding.skip")
                        Image(systemName: "chevron.right")
                            .font(.system(size: 13, weight: .semibold))
                    }
                    .zenFont(16, weight: .medium, design: .rounded)
                    .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                    .frame(minHeight: 44)
                    .padding(.horizontal, 20)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressedButtonStyle())
                .opacity(isLastPage ? 0 : 1)
                .disabled(isLastPage)
                .animation(.easeOut(duration: 0.2), value: isLastPage)
    }

    private var pages: some View {
            TabView(selection: $page) {
                ForEach(Page.allCases, id: \.rawValue) { item in
                    VStack(spacing: 0) {
                        Color.clear.frame(height: Self.skipRowHeight)
                        illustration(item)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .accessibilityHidden(true)
                        Text(item.title)
                            .zenFont(30, weight: .bold, design: .rounded)
                            .foregroundStyle(Palette.ink(scheme))
                            .multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 24)
                            .padding(.top, 20)
                            .accessibilityAddTraits(.isHeader)
                    }
                    .tag(item.rawValue)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
    }

    private var continueButton: some View {
        PrimaryCapsuleButton(
            titleKey: isLastPage ? "onboarding.start" : "onboarding.continue",
            scheme: scheme
        ) {
            if isLastPage {
                onFinish()
            } else {
                withAnimation(.easeInOut(duration: 0.3)) { page += 1 }
            }
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 12)
    }

    private var dots: some View {
        HStack(spacing: 6) {
            ForEach(Page.allCases, id: \.rawValue) { item in
                Capsule()
                    .fill(Palette.ink(scheme).opacity(item.rawValue == page ? 1 : 0.22))
                    .frame(width: item.rawValue == page ? 18 : 6, height: 6)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: page)
        .accessibilityElement()
        .accessibilityLabel(Text("onboarding.page \(page + 1) \(Page.allCases.count)"))
    }

    /// The app's own paper with a light wash of the brand green at the top.
    private var backdrop: some View {
        LinearGradient(
            colors: [Palette.coral(scheme).opacity(scheme == .dark ? 0.16 : 0.09), Palette.paper(scheme)],
            startPoint: .top,
            endPoint: .center
        )
        .background(Palette.paper(scheme))
    }

    @ViewBuilder
    private func illustration(_ page: Page) -> some View {
        switch page {
        case .spaces: OnboardingSpacesIllustration(scheme: scheme)
        case .share: OnboardingShareIllustration(scheme: scheme)
        case .browser: OnboardingBrowserIllustration(scheme: scheme)
        }
    }
}

// MARK: - Illustrations

/// The made-up café guide's favicon: a cup on a coffee-brown tile.
private struct CafeFavicon: View {
    let size: CGFloat

    var body: some View {
        Image(systemName: "cup.and.saucer.fill")
            .font(.system(size: size * 0.5, weight: .semibold))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(Color(red: 0.55, green: 0.36, blue: 0.24), in: RoundedRectangle(cornerRadius: size * 0.22, style: .continuous))
    }
}

/// Made-up space themes, so the drawn screens carry colour the way a themed
/// space does in the app.
private enum OnboardingTheme {
    static let spaces = ZenSpaceTheme(gradientColors: ["#A9C9B8", "#E8D5BF"], opacity: 0.45)
    static let work = ZenSpaceTheme(gradientColors: ["#B7C7D9", "#E6D3C4"], opacity: 0.45)
}

/// The card surface the illustrations are built on: the app's own background,
/// lifted off the page by a soft shadow.
private struct IllustrationCard: ViewModifier {
    let scheme: ColorScheme
    var cornerRadius: CGFloat = 34
    /// A space theme for the surface, drawn by the app's own space backdrop.
    var theme: ZenSpaceTheme? = nil

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        content
            .background {
                if let theme {
                    ZenSpaceGradientBackground(theme: theme, scheme: scheme, darkenDots: scheme == .dark)
                        .clipShape(shape)
                } else {
                    shape.fill(Palette.background(scheme))
                }
            }
            .glassRim(cornerRadius: cornerRadius, opacity: 0.06)
            .shadow(color: Color.black.opacity(scheme == .dark ? 0.4 : 0.12), radius: 28, y: 16)
    }
}

/// The spaces screen in small, built like the real one: essentials, a space
/// with its tabs, and at the bottom the space switcher and the action bar.
/// It shrinks to fit a short screen instead of being cut off.
private struct OnboardingSpacesIllustration: View {
    let scheme: ColorScheme

    private static let cardSize = CGSize(width: 292, height: 452)
    /// Room around the card for its shadow.
    private static let shadowRoom: CGFloat = 28

    private let essentials = ["fav-discord", "fav-nytimes", "fav-chatgpt", "fav-reddit", "fav-github", "fav-youtube"]

    private struct Row: Identifiable {
        let id: String
        let icon: String
    }

    private let pinned: [Row] = [
        Row(id: "ChatGPT", icon: "fav-chatgpt"),
        Row(id: "YouTube", icon: "fav-youtube"),
        Row(id: "Google Docs", icon: "fav-googledocs")
    ]

    var body: some View {
        GeometryReader { geo in
            let available = geo.size.height - 2 * Self.shadowRoom
            let scale = min(1, max(0.5, available / Self.cardSize.height))
            // Centred in the space above the headline; scaled about its
            // centre, so a short screen shrinks it evenly toward the middle.
            card
                .scaleEffect(scale)
                .frame(width: geo.size.width, height: geo.size.height)
        }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 12) {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 7), count: 3), spacing: 7) {
                ForEach(essentials, id: \.self) { icon in
                    favicon(icon, size: 22)
                        .frame(maxWidth: .infinity)
                        .frame(height: 46)
                        .liftCard(scheme, cornerRadius: 12)
                }
            }

            HStack(spacing: 10) {
                Image("zen-leaf")
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 20, height: 20)
                    .foregroundStyle(Palette.coral(scheme))
                Text(verbatim: "Personal")
                    .font(.system(size: 18, weight: .bold, design: .rounded))
                    .foregroundStyle(Palette.ink(scheme))
            }
            .padding(.top, 4)

            VStack(alignment: .leading, spacing: 14) {
                ForEach(pinned) { row($0) }
                Rectangle()
                    .fill(Palette.ink(scheme).opacity(0.15))
                    .frame(height: 1)
                row(Row(id: "Ten quiet cafés in Lisbon", icon: "cafe"))
            }
            .padding(.leading, 2)

            Spacer(minLength: 0)

            HStack(spacing: 22) {
                spaceIcon("zen-leaf", active: true)
                spaceIcon("zen-briefcase", active: false)
                spaceIcon("zen-music", active: false)
            }
            .frame(maxWidth: .infinity)
            .padding(.bottom, 4)

            HStack(spacing: 8) {
                chromeTile("archivebox")
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 13, weight: .semibold))
                    Text(verbatim: "Search…")
                    Spacer(minLength: 0)
                }
                .font(.system(size: 14, weight: .medium, design: .rounded))
                .foregroundStyle(Palette.ink(scheme).opacity(0.55))
                .padding(.horizontal, 14)
                .frame(height: 40)
                .liftCard(scheme, cornerRadius: 14)
                chromeTile("gearshape")
            }
        }
        .padding(16)
        .padding(.top, 2)
        .frame(width: Self.cardSize.width, height: Self.cardSize.height)
        .modifier(IllustrationCard(scheme: scheme, theme: OnboardingTheme.spaces))
    }

    private func favicon(_ name: String, size: CGFloat) -> some View {
        Image(name)
            .resizable()
            .interpolation(.high)
            .scaledToFit()
            .frame(width: size, height: size)
            .clipShape(RoundedRectangle(cornerRadius: size * 0.22, style: .continuous))
    }

    private func spaceIcon(_ name: String, active: Bool) -> some View {
        Image(name)
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .frame(width: 18, height: 18)
            .foregroundStyle(active ? Palette.coral(scheme) : Palette.ink(scheme).opacity(0.3))
    }

    private func chromeTile(_ symbol: String) -> some View {
        Image(systemName: symbol)
            .font(.system(size: 15, weight: .medium))
            .foregroundStyle(Palette.ink(scheme).opacity(0.7))
            .frame(width: 40, height: 40)
            .liftCard(scheme, cornerRadius: 14)
    }

    private func row(_ row: Row) -> some View {
        HStack(spacing: 12) {
            if row.icon == "cafe" {
                CafeFavicon(size: 18)
            } else {
                favicon(row.icon, size: 18)
            }
            Text(verbatim: row.id)
                .font(.system(size: 15, design: .rounded))
                .foregroundStyle(Palette.ink(scheme))
                .lineLimit(1)
        }
    }
}

/// Sharing: Z-Sync's own save screen fills the space, and the system share
/// sheet sits over its lower part like the dialog it was picked from. The
/// whole picture fades out at the bottom.
private struct OnboardingShareIllustration: View {
    let scheme: ColorScheme

    private static let width: CGFloat = 330
    /// The share sheet picture's own aspect ratio (1608 × 1216).
    private static let sheetHeight: CGFloat = width * 1216 / 1608
    /// Room around the card for its shadow, kept inside the fade's mask.
    private static let shadowRoom: CGFloat = 32

    var body: some View {
        ZStack(alignment: .bottom) {
            saveScreen
            Image("OnboardingShareSheet")
                .resizable()
                .interpolation(.high)
                .scaledToFit()
                .frame(width: Self.width)
        }
        .frame(width: Self.width)
        .frame(maxHeight: 600)
        .padding(.horizontal, Self.shadowRoom)
        .padding(.top, Self.shadowRoom)
        .mask {
            LinearGradient(
                // Short, so only the empty edge under the app names fades.
                stops: [.init(color: .black, location: 0.95), .init(color: .clear, location: 1)],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .padding(.horizontal, -Self.shadowRoom)
        .padding(.top, -Self.shadowRoom)
        .padding(.top, 8)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    /// Z-Sync's save screen as the share extension draws it: the page's
    /// favicon, title and address, and the space it goes into.
    private var saveScreen: some View {
        VStack(spacing: 0) {
            // Near the top, so it keeps clear of the share sheet below.
            Color.clear.frame(height: 52)
            CafeFavicon(size: 64)
                .padding(.bottom, 16)
            Text(verbatim: "Ten quiet cafés in Lisbon")
                .zenFont(21, weight: .semibold, design: .rounded)
                .foregroundStyle(Palette.ink(scheme))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
            Text(verbatim: "https://lisbon-guide.com/quiet-cafes")
                .zenFont(13, design: .rounded)
                .foregroundStyle(Palette.ink(scheme).opacity(0.55))
                .padding(.top, 6)
            HStack(spacing: 8) {
                Image("zen-briefcase")
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 18, height: 18)
                    .foregroundStyle(Palette.coral(scheme))
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.5))
                Text(verbatim: "Work")
                    .zenFont(16, weight: .semibold, design: .rounded)
                    .foregroundStyle(Palette.ink(scheme))
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 10)
            .modifier(DomedFill(shape: Capsule(style: .continuous), scheme: scheme))
            .glassRimCapsule()
            .padding(.top, 22)
            Spacer(minLength: 20)
            // The lower part is under the share sheet.
            Color.clear.frame(height: Self.sheetHeight * 0.72)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .modifier(IllustrationCard(scheme: scheme, cornerRadius: 40, theme: OnboardingTheme.work))
    }
}

/// The browser, drawn like the other pages: the café page from the share
/// page, open with the browser's real chrome (the same glass address bar and
/// dock, at their real sizes), and two more open pages behind it as cards.
/// The page is laid out at phone size and shrunk as a whole, so the chrome
/// keeps its true proportions.
private struct OnboardingBrowserIllustration: View {
    let scheme: ColorScheme

    /// The page at the size of a phone screen.
    private static let pageSize = CGSize(width: 390, height: 720)
    /// How small the page is drawn at most.
    private static let maxScale: CGFloat = 0.68
    private static let shadowRoom: CGFloat = 28

    var body: some View {
        GeometryReader { geo in
            let available = geo.size.height - 2 * Self.shadowRoom
            let scale = min(Self.maxScale, max(0.35, available / Self.pageSize.height))
            ZStack {
                tabSwitcher
                    .rotationEffect(.degrees(-8))
                    .offset(x: -58 / Self.maxScale, y: 14 / Self.maxScale)
                youtubePage
                    .rotationEffect(.degrees(8))
                    .offset(x: 58 / Self.maxScale, y: 14 / Self.maxScale)
                page
            }
            .frame(width: Self.pageSize.width, height: Self.pageSize.height)
            .scaleEffect(scale)
            .frame(width: geo.size.width, height: geo.size.height)
        }
    }

    private static let backSize = CGSize(width: 390 * 0.9, height: 720 * 0.88)

    /// A page behind the one in front: white like a website, rounded like
    /// a card in the overview.
    private func backCard<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        content()
            .frame(width: Self.backSize.width, height: Self.backSize.height, alignment: .top)
            .background(Color.white)
            .clipShape(RoundedRectangle(cornerRadius: 44, style: .continuous))
            .glassRim(cornerRadius: 44, opacity: 0.06)
            .shadow(color: Color.black.opacity(scheme == .dark ? 0.4 : 0.12), radius: 24, y: 12)
    }

    private func textLines(_ widths: [CGFloat], width: CGFloat, height: CGFloat = 10, color: Color = Color(white: 0.87)) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(widths.enumerated()), id: \.offset) { _, share in
                Capsule().fill(color).frame(width: width * share, height: height)
            }
        }
    }

    /// The open pages as the browser's switcher shows them, scrolled all the
    /// way back: the first page in focus, named above, and the later ones
    /// running off to the right on top of it.
    private var tabSwitcher: some View {
        let width = Self.backSize.width
        let cardScale: CGFloat = 0.6875
        let card = CGSize(width: width * cardScale, height: 400)
        // The deck's spacing with the first card in focus, moved left so
        // the card shows beside the page in front.
        let lefts: [CGFloat] = [0.07, 0.57, 1.71]
        return ZStack(alignment: .topLeading) {
            Palette.background(scheme)
            ForEach(0..<3, id: \.self) { index in
                deckCard(index)
                    .frame(width: card.width, height: card.height)
                    .clipShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
                    .shadow(color: .black.opacity(0.26), radius: 14, x: -3)
                    .overlay(alignment: .topLeading) {
                        deckHeader(index).offset(x: 20, y: -30)
                    }
                    .offset(x: width * lefts[index], y: 120)
            }
        }
        .frame(width: width, height: Self.backSize.height)
        .clipShape(RoundedRectangle(cornerRadius: 44, style: .continuous))
        .glassRim(cornerRadius: 44, opacity: 0.06)
        .shadow(color: Color.black.opacity(scheme == .dark ? 0.4 : 0.12), radius: 24, y: 12)
    }

    @ViewBuilder
    private func deckHeader(_ index: Int) -> some View {
        HStack(spacing: 8) {
            switch index {
            case 0:
                Image("fav-wikipedia").resizable().scaledToFit().frame(width: 22, height: 22)
                    .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
            case 1:
                Image("fav-youtube").resizable().scaledToFit().frame(width: 22, height: 22)
                    .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
            default:
                CafeFavicon(size: 22)
            }
            // Only the card in focus is named.
            if index == 0 {
                Text(verbatim: "Aurora – Wikipedia")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Palette.ink(scheme))
                    .lineLimit(1)
                    .frame(maxWidth: 180, alignment: .leading)
            }
        }
    }

    /// A small picture of each open page.
    @ViewBuilder
    private func deckCard(_ index: Int) -> some View {
        switch index {
        case 0:
            VStack(alignment: .leading, spacing: 8) {
                Text(verbatim: "Aurora")
                    .font(.system(size: 22, weight: .regular, design: .serif))
                    .foregroundStyle(Color(white: 0.08))
                LinearGradient(
                    colors: [Color(red: 0.05, green: 0.12, blue: 0.22), Color(red: 0.18, green: 0.75, blue: 0.52), Color(red: 0.45, green: 0.30, blue: 0.62)],
                    startPoint: .bottomLeading,
                    endPoint: .topTrailing
                )
                .frame(height: 100)
                .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
                textLines([1, 0.9, 0.96, 0.7, 1, 0.85], width: 200, height: 7)
                Spacer(minLength: 0)
            }
            .padding(16)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(Color.white)
        case 1:
            VStack(alignment: .leading, spacing: 10) {
                LinearGradient(colors: [Color(red: 0.95, green: 0.62, blue: 0.35), Color(red: 0.78, green: 0.28, blue: 0.38)], startPoint: .topLeading, endPoint: .bottomTrailing)
                    .frame(height: 135)
                textLines([0.9, 0.55], width: 200, height: 8, color: Color(white: 0.78))
                    .padding(.horizontal, 14)
                LinearGradient(colors: [Color(red: 0.25, green: 0.52, blue: 0.72), Color(red: 0.16, green: 0.30, blue: 0.48)], startPoint: .topLeading, endPoint: .bottomTrailing)
                    .frame(height: 135)
                    .padding(.top, 8)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(Color.white)
        default:
            VStack(alignment: .leading, spacing: 10) {
                Image("OnboardingCafe")
                    .resizable()
                    .scaledToFill()
                    .frame(height: 150)
                    .frame(maxWidth: .infinity)
                    .clipped()
                Text(verbatim: "Ten quiet cafés in Lisbon")
                    .font(.system(size: 19, weight: .bold, design: .serif))
                    .foregroundStyle(Color(white: 0.1))
                    .padding(.horizontal, 14)
                textLines([1, 0.92, 0.97, 0.6], width: 210, height: 7)
                    .padding(.horizontal, 14)
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(Color.white)
        }
    }

    /// A video site: its header, a video with its title and channel, and the
    /// start of the next one.
    private var youtubePage: some View {
        backCard {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 8) {
                    Image("fav-youtube")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 30, height: 30)
                    Text(verbatim: "YouTube")
                        .font(.system(size: 19, weight: .bold))
                        .tracking(-0.6)
                    Spacer(minLength: 0)
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 17, weight: .medium))
                    Circle()
                        .fill(Color(red: 0.55, green: 0.42, blue: 0.85))
                        .frame(width: 26, height: 26)
                }
                .foregroundStyle(Color(white: 0.1))
                .padding(.horizontal, 20)
                .padding(.top, 26)
                .padding(.bottom, 14)
                video(colors: [Color(red: 0.95, green: 0.62, blue: 0.35), Color(red: 0.78, green: 0.28, blue: 0.38)], title: [0.92, 0.6])
                video(colors: [Color(red: 0.25, green: 0.52, blue: 0.72), Color(red: 0.16, green: 0.30, blue: 0.48)], title: [0.85, 0.5])
                    .padding(.top, 18)
            }
        }
    }

    private func video(colors: [Color], title: [CGFloat]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            ZStack {
                LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)
                Image(systemName: "play.fill")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 56, height: 40)
                    .background(Color.black.opacity(0.35), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .frame(height: 190)
            HStack(alignment: .top, spacing: 12) {
                Circle()
                    .fill(colors[1].opacity(0.8))
                    .frame(width: 36, height: 36)
                VStack(alignment: .leading, spacing: 9) {
                    textLines(title, width: Self.backSize.width - 88, height: 11, color: Color(white: 0.78))
                    Capsule().fill(Color(white: 0.9)).frame(width: 140, height: 9)
                }
            }
            .padding(.horizontal, 18)
        }
    }

    /// The café page, with the soft blur and the chrome over its bottom.
    private var page: some View {
        ZStack(alignment: .bottom) {
            article
            PageBottomFade()
            chrome
                .padding(.horizontal, 16)
                .padding(.bottom, 20)
        }
        .frame(width: Self.pageSize.width, height: Self.pageSize.height)
        .background(Color.white)
        .clipShape(RoundedRectangle(cornerRadius: 50, style: .continuous))
        .glassRim(cornerRadius: 50, opacity: 0.06)
        .shadow(color: Color.black.opacity(scheme == .dark ? 0.45 : 0.16), radius: 34, y: 18)
    }

    /// A plain article: the photo, a headline and its text, running on under
    /// the chrome so the glass has a page to sit on.
    private var article: some View {
        VStack(alignment: .leading, spacing: 0) {
            Image("OnboardingCafe")
                .resizable()
                .scaledToFill()
                .frame(width: Self.pageSize.width, height: 250)
                .clipped()
            VStack(alignment: .leading, spacing: 12) {
                Text(verbatim: "GUIDE")
                    .font(.system(size: 13, weight: .semibold))
                    .tracking(1.2)
                    .foregroundStyle(Color(white: 0.45))
                Text(verbatim: "Ten quiet cafés in Lisbon")
                    .font(.system(size: 31, weight: .bold, design: .serif))
                    .foregroundStyle(Color(white: 0.1))
                    .fixedSize(horizontal: false, vertical: true)
                ForEach(Array([1.0, 0.95, 0.98, 0.9, 0.97, 0.6, 1.0, 0.92, 0.96].enumerated()), id: \.offset) { _, width in
                    Capsule()
                        .fill(Color(white: 0.87))
                        .frame(width: (Self.pageSize.width - 48) * width, height: 10)
                }
                .padding(.top, 4)
            }
            .padding(.horizontal, 24)
            .padding(.top, 22)
            Spacer(minLength: 0)
        }
        .frame(width: Self.pageSize.width, height: Self.pageSize.height, alignment: .top)
    }

    /// The browser's chrome as `MiniBrowserChrome` lays it out: the address
    /// bar (as `MiniBrowserTopBar` draws a page's bar) over the dock.
    private var chrome: some View {
        VStack(spacing: MiniBrowserChromeMetrics.rowSpacing) {
            addressBar
            HStack(spacing: 10) {
                PageCountBadge(count: 3)
                    .foregroundStyle(Palette.ink(scheme))
                    .frame(width: MiniBrowserChromeMetrics.dockHeight, height: MiniBrowserChromeMetrics.dockHeight)
                    .floatingGlassCapsule()
                Spacer(minLength: 0)
                Image(systemName: "plus")
                    .font(.system(size: 19, weight: .semibold))
                    .foregroundStyle(Palette.ink(scheme))
                    .frame(width: 92, height: 42)
                    .floatingGlassCapsule()
                Spacer(minLength: 0)
                Image(systemName: "xmark")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Palette.ink(scheme))
                    .frame(width: MiniBrowserChromeMetrics.dockHeight, height: MiniBrowserChromeMetrics.dockHeight)
                    .floatingGlassCapsule()
            }
            .padding(.horizontal, 7)
        }
    }

    /// Back only, since forward leads nowhere here; the domain sits in the
    /// middle of the bar, as on a real page.
    private var addressBar: some View {
        HStack(spacing: 2) {
            barGlyph("chevron.backward")
            Text(verbatim: "lisbon-guide.com")
                .zenFont(16, weight: .semibold, design: .rounded)
                .foregroundStyle(Palette.ink(scheme))
                .lineLimit(1)
                .frame(maxWidth: .infinity)
                // The menu and reload take one slot more than back: this
                // puts the domain back in the middle of the whole bar.
                .padding(.trailing, -44)
            barGlyph("ellipsis", upright: true)
            barGlyph("arrow.clockwise")
        }
        .padding(.horizontal, 8)
        .frame(height: MiniBrowserChromeMetrics.navBarHeight)
        .floatingGlassCapsule()
    }

    private func barGlyph(_ systemName: String, upright: Bool = false) -> some View {
        Image(systemName: systemName)
            .font(.system(size: 18, weight: .semibold))
            .rotationEffect(.degrees(upright ? 90 : 0))
            .foregroundStyle(Palette.ink(scheme).opacity(0.85))
            .frame(width: 32, height: MiniBrowserChromeMetrics.navBarHeight - 8)
            .frame(minWidth: 44, minHeight: 44)
    }
}

#Preview {
    OnboardingView(onFinish: {})
}
