import SwiftUI

private extension BlockCategory {
    var symbol: String {
        switch self {
        case .ads: return "hand.raised"
        case .trackers: return "eye.slash"
        case .cookieBanners: return "checkmark.seal"
        }
    }

    var title: LocalizedStringKey {
        switch self {
        case .ads: return "blocking.ads"
        case .trackers: return "blocking.trackers"
        case .cookieBanners: return "blocking.cookie_banners"
        }
    }
}

/// The three switches, in the card style of the settings sheet.
private struct BlockingSwitches: View {
    let scheme: ColorScheme
    let isOn: (BlockCategory) -> Bool
    let onChange: (BlockCategory, Bool) -> Void
    var isEnabled: (BlockCategory) -> Bool = { _ in true }
    var showsDetail = true

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(BlockCategory.allCases.enumerated()), id: \.element) { index, category in
                if index > 0 {
                    Rectangle()
                        .fill(Palette.ink(scheme).opacity(0.15))
                        .frame(height: 0.5)
                        .padding(.leading, 44)
                }
                row(category)
            }
        }
        .liftCard(scheme)
    }

    private func row(_ category: BlockCategory) -> some View {
        let enabled = isEnabled(category)
        return Toggle(isOn: Binding(get: { isOn(category) }, set: { onChange(category, $0) })) {
            Label {
                VStack(alignment: .leading, spacing: 1) {
                    Text(category.title)
                        .font(.system(size: 16, weight: .medium, design: .rounded))
                    if !enabled {
                        Text("blocking.off_in_settings")
                            .font(.system(size: 13, design: .rounded))
                            .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                    } else if showsDetail, category == .cookieBanners {
                        Text("blocking.cookie_banners_detail")
                            .font(.system(size: 13, design: .rounded))
                            .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                    }
                }
            } icon: {
                Image(systemName: category.symbol)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.6))
            }
            .foregroundStyle(Palette.ink(scheme))
        }
        .tint(Palette.coral(scheme))
        .disabled(!enabled)
        .padding(.horizontal, 16)
        .frame(minHeight: 54)
    }
}

/// "Content blocking" in Settings: the switches for every site.
struct ContentBlockingSettingsSection: View {
    let scheme: ColorScheme
    private var blocker: ContentBlocker { .shared }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("CONTENT BLOCKING")
                .font(.system(size: 12, weight: .semibold, design: .rounded))
                .tracking(0.8)
                .foregroundStyle(Palette.ink(scheme).opacity(0.4))
                .padding(.horizontal, 4)

            BlockingSwitches(
                scheme: scheme,
                isOn: { blocker.settings.isOn($0) },
                onChange: { category, on in blocker.update(blocker.settings.with(category, on: on)) }
            )

            caption
                .font(.system(size: 13, design: .rounded))
                .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                .padding(.horizontal, 4)
        }
    }

    private var caption: Text {
        if let date = blocker.lastModified.flatMap(FilterListUpdates.displayDate) {
            return Text("settings.content_blocking.caption \(date)")
        }
        return Text("settings.content_blocking.caption_undated")
    }
}

/// "Site Settings" from the browser menu: the switches for one site. A switch
/// that is off in Settings stays off here, with a note saying so.
struct SiteSettingsSheet: View {
    let host: String
    let scheme: ColorScheme
    /// The page reloads so the new switches apply to it.
    var onChange: () -> Void

    @Environment(\.dismiss) private var dismiss
    private var blocker: ContentBlocker { .shared }

    var body: some View {
        let site = blockingSiteKey(host)
        NavigationStack {
            VStack(alignment: .leading, spacing: 8) {
                Text(site.uppercased())
                    .font(.system(size: 12, weight: .semibold, design: .rounded))
                    .tracking(0.8)
                    .foregroundStyle(Palette.ink(scheme).opacity(0.4))
                    .padding(.horizontal, 4)

                BlockingSwitches(
                    scheme: scheme,
                    isOn: { blocker.settings.isOn($0, host: host) },
                    onChange: { category, on in
                        blocker.update(blocker.settings.withSite(category, host: host, on: on))
                        onChange()
                    },
                    isEnabled: { blocker.settings.isOn($0) },
                    showsDetail: false
                )

                Text("site_settings.footer \(site)")
                    .font(.system(size: 13, design: .rounded))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                    .padding(.horizontal, 4)

                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .background(Palette.paper(scheme))
            .navigationTitle(Text("browser.site_settings"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.done") { dismiss() }
                }
            }
        }
    }
}

/// The setup question: asked once, after sign-in or on the first launch with
/// content blocking. Every switch starts on; Continue saves them.
struct ContentBlockingSetupView: View {
    let scheme: ColorScheme
    @State private var choice = ContentBlockingSettings()

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    // No badge at the top: the title carries the screen, the
                    // same way the What's New sheet opens on its title.
                    Text("blocking.setup.title")
                        .font(.system(size: 30, weight: .bold, design: .rounded))
                        .foregroundStyle(Palette.ink(scheme))
                        .padding(.bottom, 10)
                    Text("blocking.setup.body")
                        .font(.system(size: 17, design: .rounded))
                        .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                        .padding(.bottom, 24)
                    BlockingSwitches(
                        scheme: scheme,
                        isOn: { choice.isOn($0) },
                        onChange: { category, on in choice = choice.with(category, on: on) }
                    )
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 28)
            }
            .scrollBounceBehavior(.basedOnSize)

            Button {
                ContentBlocker.shared.answerSetup(choice)
            } label: {
                Text("blocking.setup.continue")
                    .font(.system(size: 17, weight: .semibold, design: .rounded))
                    .frame(maxWidth: .infinity)
                    .frame(height: 56)
                    .foregroundStyle(Palette.onCoral(scheme))
                    .background(Palette.coral(scheme), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .buttonStyle(.plain)

            Text("blocking.setup.note")
                .font(.system(size: 13, design: .rounded))
                .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.top, 14)
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 16)
        .background(Palette.paper(scheme).ignoresSafeArea())
        .interactiveDismissDisabled()
    }
}
