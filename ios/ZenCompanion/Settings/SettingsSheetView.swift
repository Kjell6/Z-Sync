import SwiftUI

// MARK: - Settings sheet

/// The settings screen. Rows are grouped, and exactly one group leads to a
/// subpage (Content blocking); everything else changes in place. The account
/// and the "Save shared link as" choice sit on their own above the groups.
struct SettingsSheet: View {
    let account: AccountSnapshot
    let loadError: String?
    let scheme: ColorScheme
    var normalTabsCapability: NormalTabsCapability = .absent
    /// Called right after the user picks a toolbar placement, so the screen
    /// behind the sheet re-renders immediately instead of on dismissal.
    var onToolbarPlacementChange: (ToolbarPlacement) -> Void = { _ in }
    /// Same for the essentials-per-row choice, which the start-page grid reads.
    var onEssentialsColumnsChange: (EssentialsColumns) -> Void = { _ in }

    @Environment(\.dismiss) private var dismiss
    @State private var model = SettingsModel()
    @State private var isShowingSignOutConfirm: Bool = false
    @State private var isShowingWhatsNew: Bool = false
    @State private var isShowingAddEngine: Bool = false

    private var appVersionString: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "Version \(version) (\(build))"
    }

    /// The picker never shows a choice the capability forbids: a stored
    /// `.normal` reads as `.pinned` until normal tabs are supported again.
    private var displayedSaveKind: SaveKind {
        normalTabsCapability == .enabled ? model.saveKind : .pinned
    }

    var body: some View {
        @Bindable var model = model

        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    accountCard
                    saveKindCard
                    browsingSection(model: model)
                    customEnginesSection
                    layoutSection(model: model)
                    feedbackSection
                    aboutSection
                    footer
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)
                .padding(.bottom, 28)
            }
            .background(Palette.paper(scheme).ignoresSafeArea())
            .navigationTitle(Text("Settings"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    ToolbarIconButton(systemName: "xmark", accessibilityKey: "common.done") {
                        dismiss()
                    }
                }
            }
            .navigationDestination(isPresented: $isShowingAddEngine) {
                AddSearchEngineView(model: model, scheme: scheme)
            }
            .confirmationDialog(
                LocalizedStringKey(account.isDemo ? "demo.exit" : "home.sign_out"),
                isPresented: $isShowingSignOutConfirm,
                titleVisibility: .visible
            ) {
                Button(LocalizedStringKey(account.isDemo ? "demo.exit" : "home.sign_out"), role: .destructive) {
                    dismiss()
                    model.signOut()
                }
                Button("common.cancel", role: .cancel) {}
            }
            .sheet(isPresented: $isShowingWhatsNew) {
                WhatsNewSheet(release: WhatsNewRelease.latest, scheme: scheme)
                    .presentationDetents([.large])
                    .presentationDragIndicator(.visible)
            }
        }
    }

    // MARK: Account

    /// Email and the destructive action live in one card: signing out belongs
    /// to the account, and it no longer needs a button of its own next to it.
    private var accountCard: some View {
        VStack(spacing: 0) {
            VStack(spacing: 0) {
                HStack(spacing: 12) {
                    Image(systemName: "person.crop.circle")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Palette.ink(scheme).opacity(0.6))

                    VStack(alignment: .leading, spacing: 2) {
                        Text(account.isDemo ? String(localized: "demo.account_name") : account.email)
                            .font(.system(size: 16, weight: .medium, design: .rounded))
                            .foregroundStyle(Palette.ink(scheme))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                        if account.isDemo {
                            Text("demo.account_caption")
                                .font(.system(size: 12, design: .rounded))
                                .foregroundStyle(Palette.ink(scheme).opacity(0.5))
                        }
                    }

                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 16)
                .frame(minHeight: 56)

                SettingsHairline(scheme: scheme)

                Button(role: .destructive) {
                    isShowingSignOutConfirm = true
                } label: {
                    SettingsRow(
                        title: LocalizedStringKey(account.isDemo ? "demo.exit" : "home.sign_out"),
                        systemImage: "rectangle.portrait.and.arrow.right",
                        scheme: scheme,
                        color: .red
                    ) {
                        EmptyView()
                    }
                }
                .buttonStyle(PressedButtonStyle())
            }
            .liftCard(scheme)

            if let loadError {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "exclamationmark.circle.fill")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.orange)
                        .padding(.top, 1)

                    Text(loadError)
                        .font(.system(size: 13, design: .rounded))
                        .foregroundStyle(Palette.ink(scheme).opacity(0.75))
                        .lineSpacing(2)
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    Color.orange.opacity(scheme == .dark ? 0.12 : 0.08),
                    in: RoundedRectangle(cornerRadius: 14, style: .continuous)
                )
                .padding(.top, 8)
            }
        }
    }

    // MARK: Save shared link

    @ViewBuilder
    private var saveKindCard: some View {
        if normalTabsCapability != .absent {
            VStack(alignment: .leading, spacing: 8) {
                VStack(spacing: 0) {
                    SettingsRow(title: "settings.save_kind", systemImage: "pin", scheme: scheme) {
                        Menu {
                            ForEach(SaveKind.allCases) { kind in
                                Button {
                                    model.saveKind = kind
                                } label: {
                                    if displayedSaveKind == kind {
                                        Label(kind.title, systemImage: "checkmark")
                                    } else {
                                        Text(kind.title)
                                    }
                                }
                                .disabled(kind == .normal && normalTabsCapability != .enabled)
                            }
                        } label: {
                            SettingsMenuValue(text: displayedSaveKind.title, scheme: scheme)
                        }
                    }
                }
                .liftCard(scheme)

                if normalTabsCapability == .disabled {
                    Text("settings.save_kind.normal_disabled_caption")
                        .font(.system(size: 12, design: .rounded))
                        .foregroundStyle(Palette.ink(scheme).opacity(0.45))
                        .padding(.horizontal, 4)
                }
            }
        }
    }

    // MARK: Browsing

    private func browsingSection(model: SettingsModel) -> some View {
        SettingsSection(title: "BROWSING", scheme: scheme) {
            VStack(spacing: 0) {
                engineRow(model: model)

                SettingsHairline(scheme: scheme)

                SettingsToggleRow(
                    title: "settings.open_into_search",
                    systemImage: "keyboard",
                    scheme: scheme,
                    subtitle: "settings.open_into_search.subtitle",
                    isOn: $model.openIntoSearch
                )

                SettingsHairline(scheme: scheme)

                NavigationLink {
                    ContentBlockingSettingsPage(scheme: scheme)
                } label: {
                    SettingsRow(title: "settings.content_blocking", systemImage: "shield.lefthalf.filled", scheme: scheme) {
                        SettingsChevron(scheme: scheme)
                    }
                }
                .buttonStyle(PressedButtonStyle())

                SettingsHairline(scheme: scheme)

                SettingsToggleRow(
                    title: "settings.external_browser",
                    systemImage: "safari",
                    scheme: scheme,
                    isOn: $model.alwaysOpenExternally
                )
            }
            .liftCard(scheme)
        }
    }

    /// The engines are picked from a menu on the row itself — the list is
    /// short enough that a page would only add a tap. Custom engines are
    /// added from the same menu and managed in their own card below.
    private func engineRow(model: SettingsModel) -> some View {
        Menu {
            ForEach(model.availableSearchEngines) { engine in
                Button {
                    model.selectedSearchEngine = engine
                } label: {
                    if model.selectedSearchEngine.id == engine.id {
                        Label(engine.displayName, systemImage: "checkmark")
                    } else {
                        Text(engine.displayName)
                    }
                }
            }

            Divider()

            Button {
                isShowingAddEngine = true
            } label: {
                Label("settings.search_engine.add", systemImage: "plus")
            }
        } label: {
            SettingsRow(title: "settings.search_engine", systemImage: "magnifyingglass", scheme: scheme) {
                SettingsMenuValue(text: model.selectedSearchEngine.displayName, scheme: scheme)
            }
        }
    }

    @ViewBuilder
    private var customEnginesSection: some View {
        let engines = model.customSearchEngines
        if !engines.isEmpty {
            SettingsSection(title: "settings.search_engine.custom_section", scheme: scheme) {
                VStack(spacing: 0) {
                    ForEach(Array(engines.enumerated()), id: \.element.id) { index, engine in
                        HStack(spacing: 12) {
                            Text(engine.displayName)
                                .font(.system(size: 16, weight: .medium, design: .rounded))
                                .foregroundStyle(Palette.ink(scheme))
                            Spacer(minLength: 8)
                            Button(role: .destructive) {
                                model.deleteCustomSearchEngine(engine)
                            } label: {
                                Image(systemName: "trash")
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundStyle(Color.red.opacity(0.8))
                            }
                            .buttonStyle(PressedButtonStyle())
                            .accessibilityLabel(Text("Delete"))
                        }
                        .padding(.horizontal, 16)
                        .frame(minHeight: 54)

                        if index < engines.count - 1 {
                            SettingsHairline(scheme: scheme)
                        }
                    }
                }
                .liftCard(scheme)
            }
        }
    }

    // MARK: Layout

    private func layoutSection(model: SettingsModel) -> some View {
        SettingsSection(title: "LAYOUT", scheme: scheme) {
            VStack(spacing: 0) {
                SettingsRow(title: "settings.essentials_columns", systemImage: "rectangle.grid.3x2", scheme: scheme) {
                    Menu {
                        ForEach(EssentialsColumns.allCases) { columns in
                            Button {
                                model.essentialsColumns = columns
                                onEssentialsColumnsChange(columns)
                            } label: {
                                if model.essentialsColumns == columns {
                                    Label(columns.title, systemImage: "checkmark")
                                } else {
                                    Text(columns.title)
                                }
                            }
                        }
                    } label: {
                        SettingsMenuValue(text: model.essentialsColumns.title, scheme: scheme)
                    }
                }

                SettingsHairline(scheme: scheme)

                SettingsRow(title: "settings.toolbar", systemImage: "arrow.up.and.down", scheme: scheme) {
                    Menu {
                        ForEach(ToolbarPlacement.allCases) { placement in
                            Button {
                                model.toolbarPlacement = placement
                                onToolbarPlacementChange(placement)
                            } label: {
                                if model.toolbarPlacement == placement {
                                    Label(placement.title, systemImage: "checkmark")
                                } else {
                                    Text(placement.title)
                                }
                            }
                        }
                    } label: {
                        SettingsMenuValue(text: model.toolbarPlacement.title, scheme: scheme)
                    }
                }
            }
            .liftCard(scheme)
        }
    }

    // MARK: Feedback

    private var feedbackSection: some View {
        SettingsSection(title: "FEEDBACK", scheme: scheme) {
            VStack(spacing: 0) {
                Link(destination: Legal.feedbackURL) {
                    SettingsRow(title: "legal.feedback", systemImage: "bubble.left.and.text.bubble.right", scheme: scheme) {
                        SettingsExternalMark(scheme: scheme)
                    }
                }

                SettingsHairline(scheme: scheme)

                Link(destination: Legal.writeReviewURL) {
                    SettingsRow(title: "legal.write_review", systemImage: "star", scheme: scheme) {
                        EmptyView()
                    }
                }
                .buttonStyle(PressedButtonStyle())
            }
            .liftCard(scheme)
        }
    }

    // MARK: About

    private var aboutSection: some View {
        SettingsSection(title: "ABOUT", scheme: scheme) {
            VStack(spacing: 0) {
                Button {
                    isShowingWhatsNew = true
                } label: {
                    SettingsRow(title: "settings.whats_new", systemImage: "sparkles", scheme: scheme) {
                        SettingsChevron(scheme: scheme)
                    }
                }
                .buttonStyle(PressedButtonStyle())

                SettingsHairline(scheme: scheme)

                Link(destination: Legal.publicPolicyURL) {
                    SettingsRow(title: "legal.privacy", systemImage: "hand.raised", scheme: scheme) {
                        SettingsExternalMark(scheme: scheme)
                    }
                }
            }
            .liftCard(scheme)
        }
    }

    // MARK: Footer

    /// Version, disclaimer and the icon attribution. The disclaimer leads: it
    /// is the legal notice, so it sits above the attribution and reads one
    /// step larger.
    private var footer: some View {
        VStack(spacing: 6) {
            Text(appVersionString)
                .font(.system(size: 12, design: .rounded))
                .foregroundStyle(Palette.ink(scheme).opacity(0.4))

            Text("home.disclaimer")
                .font(.system(size: 13, weight: .regular, design: .rounded))
                .foregroundStyle(Palette.ink(scheme).opacity(0.4))

            Text("home.license_attribution")
                .font(.system(size: 11, weight: .regular, design: .rounded))
                .foregroundStyle(Palette.ink(scheme).opacity(0.35))
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.top, 2)
    }
}

// MARK: - Content blocking

/// The one page under Settings: the three switches for every site plus what
/// feeds them. Nothing else lives here, which is why it stays a page of its
/// own instead of six rows on the main screen.
private struct ContentBlockingSettingsPage: View {
    let scheme: ColorScheme

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                ContentBlockingSettingsView(scheme: scheme)
            }
            .padding(.horizontal, 20)
            .padding(.top, 16)
            .padding(.bottom, 32)
        }
        .background(Palette.paper(scheme).ignoresSafeArea())
        .navigationTitle(Text("settings.content_blocking"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Row building blocks

/// Uppercase label above a card.
private struct SettingsSection<Content: View>: View {
    let title: LocalizedStringKey
    let scheme: ColorScheme
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.system(size: 12, weight: .semibold, design: .rounded))
                .tracking(0.8)
                .foregroundStyle(Palette.ink(scheme).opacity(0.4))
                .padding(.horizontal, 4)

            content
        }
    }
}

private struct SettingsHairline: View {
    let scheme: ColorScheme

    var body: some View {
        Rectangle()
            .fill(Palette.ink(scheme).opacity(0.15))
            .frame(height: 0.5)
            .padding(.leading, 44)
    }
}

/// Icon, title and an optional subtitle on the left; the caller supplies what
/// goes on the right. Sized like every other row in the app.
private struct SettingsRow<Trailing: View>: View {
    let title: LocalizedStringKey
    let systemImage: String
    let scheme: ColorScheme
    var subtitle: LocalizedStringKey? = nil
    var color: Color? = nil
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        let tint = color ?? Palette.ink(scheme)
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(tint.opacity(0.6))
                .frame(width: 20, alignment: .center)

            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.system(size: 16, weight: .medium, design: .rounded))
                    .foregroundStyle(tint)
                if let subtitle {
                    Text(subtitle)
                        .font(.system(size: 12, design: .rounded))
                        .foregroundStyle(Palette.ink(scheme).opacity(0.5))
                }
            }

            Spacer(minLength: 8)

            trailing()
        }
        .padding(.horizontal, 16)
        .frame(minHeight: subtitle == nil ? 54 : 62)
        .contentShape(Rectangle())
    }
}

/// The same row shape, but the whole row toggles and the switch sits right.
private struct SettingsToggleRow: View {
    let title: LocalizedStringKey
    let systemImage: String
    let scheme: ColorScheme
    var subtitle: LocalizedStringKey? = nil
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                    .frame(width: 20, alignment: .center)

                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                        .font(.system(size: 16, weight: .medium, design: .rounded))
                        .foregroundStyle(Palette.ink(scheme))
                    if let subtitle {
                        Text(subtitle)
                            .font(.system(size: 12, design: .rounded))
                            .foregroundStyle(Palette.ink(scheme).opacity(0.5))
                    }
                }
            }
        }
        .tint(Palette.coral(scheme))
        .padding(.horizontal, 16)
        .frame(minHeight: subtitle == nil ? 54 : 62)
    }
}

private struct SettingsChevron: View {
    let scheme: ColorScheme

    var body: some View {
        Image(systemName: "chevron.right")
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(Palette.ink(scheme).opacity(0.3))
    }
}

private struct SettingsExternalMark: View {
    let scheme: ColorScheme

    var body: some View {
        Image(systemName: "arrow.up.right")
            .font(.system(size: 12, weight: .semibold))
            .foregroundStyle(Palette.ink(scheme).opacity(0.3))
    }
}

private struct SettingsMenuValue: View {
    let text: String
    let scheme: ColorScheme

    var body: some View {
        HStack(spacing: 4) {
            Text(text)
                .font(.system(size: 15.5, design: .rounded))
            Image(systemName: "chevron.up.chevron.down")
                .font(.system(size: 10, weight: .semibold))
        }
        .foregroundStyle(Palette.ink(scheme).opacity(0.65))
    }
}

// MARK: - Add custom search engine

/// Own page for adding a user-defined engine. A custom engine is just a URL
/// template with a `{query}` placeholder; the "paste a search link" shortcut
/// derives that template from a real search URL. On success the new engine is
/// selected and this page pops back to the settings screen.
private struct AddSearchEngineView: View {
    @Bindable var model: SettingsModel
    let scheme: ColorScheme

    @Environment(\.dismiss) private var dismiss
    @State private var draftName = ""
    @State private var draftTemplate = ""
    @State private var pastedURL = ""
    @State private var validationError: SearchEngineValidation?
    @State private var didAttemptAdd = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                pasteCard
                orSeparator
                manualCard

                if didAttemptAdd, let validationError {
                    Text(message(for: validationError))
                        .font(.system(size: 13, design: .rounded))
                        .foregroundStyle(.orange)
                        .padding(.horizontal, 4)
                }

                addButton
            }
            .padding(.horizontal, 20)
            .padding(.top, 16)
            .padding(.bottom, 32)
        }
        .background(Palette.paper(scheme).ignoresSafeArea())
        .navigationTitle(Text("Add Search Engine"))
        .navigationBarTitleDisplayMode(.inline)
    }

    private var pasteCard: some View {
        VStack(spacing: 0) {
            field(
                title: "Paste a search link",
                text: $pastedURL,
                placeholder: "https://example.com/?q=hello",
                hint: "Search once in the engine you want, copy the address, paste it here.",
                isURL: true
            )
        }
        .liftCard(scheme)
        .onChange(of: pastedURL) { _, newValue in
            guard let derived = SearchEngineTemplate.derive(fromPastedURL: newValue) else { return }
            draftTemplate = derived
            if draftName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
               let name = SearchEngineTemplate.suggestedName(fromTemplate: derived) {
                draftName = name
            }
            validationError = nil
        }
    }

    private var manualCard: some View {
        VStack(spacing: 0) {
            field(title: "Name", text: $draftName, placeholder: "My search", isURL: false)
            fieldDivider
            field(
                title: "Search URL",
                text: $draftTemplate,
                placeholder: "https://example.com/search?q={query}",
                hint: "Replace the search term with {query}.",
                isURL: true
            )
        }
        .liftCard(scheme)
    }

    private var addButton: some View {
        Button {
            didAttemptAdd = true
            if let error = model.addCustomSearchEngine(name: draftName, template: draftTemplate) {
                validationError = error
            } else {
                dismiss()
            }
        } label: {
            Text("Add Search Engine")
                .font(.system(size: 15.5, weight: .semibold, design: .rounded))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 48)
                .background(Palette.coral(scheme), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(PressedButtonStyle())
    }

    private var fieldDivider: some View {
        Rectangle()
            .fill(Palette.ink(scheme).opacity(0.15))
            .frame(height: 0.5)
            .padding(.horizontal, 16)
    }

    /// "or enter it manually" — makes it explicit that the shortcut above and
    /// the fields below are two ways to do the same thing, not two steps.
    private var orSeparator: some View {
        HStack(spacing: 10) {
            dividerLine
            Text("or enter it manually")
                .font(.system(size: 12, weight: .medium, design: .rounded))
                .foregroundStyle(Palette.ink(scheme).opacity(0.4))
                .fixedSize()
            dividerLine
        }
        .padding(.horizontal, 4)
    }

    private var dividerLine: some View {
        Rectangle()
            .fill(Palette.ink(scheme).opacity(0.15))
            .frame(maxWidth: .infinity)
            .frame(height: 0.5)
    }

    private func field(
        title: LocalizedStringKey,
        text: Binding<String>,
        placeholder: String,
        hint: LocalizedStringKey? = nil,
        isURL: Bool
    ) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.system(size: 12, weight: .medium, design: .rounded))
                .foregroundStyle(Palette.ink(scheme).opacity(0.5))
            TextField(placeholder, text: text)
                .font(.system(size: 15, design: .rounded))
                .foregroundStyle(Palette.ink(scheme))
                .textInputAutocapitalization(isURL ? .never : .words)
                .autocorrectionDisabled()
                .keyboardType(isURL ? .URL : .default)
            if let hint {
                Text(hint)
                    .font(.system(size: 11, design: .rounded))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.4))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private func message(for error: SearchEngineValidation) -> LocalizedStringKey {
        switch error {
        case .emptyName: return "Enter a name for the search engine."
        case .invalidURL: return "Enter a valid http:// or https:// URL."
        case .missingPlaceholder: return "Add {query} where the search term should go."
        }
    }
}
