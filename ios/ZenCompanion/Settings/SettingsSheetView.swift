import SwiftUI

// MARK: - Settings sheet

struct SettingsSheet: View {
    let account: AccountSnapshot
    let loadError: String?
    let scheme: ColorScheme
    var normalTabsCapability: NormalTabsCapability = .absent

    @Environment(\.dismiss) private var dismiss
    @State private var model = SettingsModel()
    @State private var isShowingSignOutConfirm: Bool = false

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
                VStack(spacing: 24) {
                    // MARK: - 1. Account Section
                    VStack(alignment: .leading, spacing: 8) {
                        Text("ACCOUNT")
                            .font(.system(size: 12, weight: .semibold, design: .rounded))
                            .tracking(0.8)
                            .foregroundStyle(Palette.ink(scheme).opacity(0.4))
                            .padding(.horizontal, 4)

                        HStack(spacing: 10) {
                            // Left: Email Info Card
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
                            .frame(height: 54)
                            .background(Palette.lift(scheme), in: RoundedRectangle(cornerRadius: 16, style: .continuous))

                            // Right: Sign Out Icon-Only Card (Narrower, compact sleek card)
                            Button(role: .destructive) {
                                isShowingSignOutConfirm = true
                            } label: {
                                Image(systemName: "rectangle.portrait.and.arrow.right")
                                    .font(.system(size: 16.5, weight: .semibold))
                                    .foregroundStyle(Color.red)
                                    .offset(x: 2.5) // Shifts icon right so left and right edge margins are identical
                                    .frame(width: 44, height: 54)
                                    .background(Color.red.opacity(scheme == .dark ? 0.15 : 0.08), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text("home.sign_out"))
                        }

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
                            .background(Color.orange.opacity(scheme == .dark ? 0.12 : 0.08), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                    }

                    // MARK: - 2. Preferences
                    VStack(alignment: .leading, spacing: 8) {
                        Text("PREFERENCES")
                            .font(.system(size: 12, weight: .semibold, design: .rounded))
                            .tracking(0.8)
                            .foregroundStyle(Palette.ink(scheme).opacity(0.4))
                            .padding(.horizontal, 4)

                        VStack(spacing: 0) {
                            NavigationLink {
                                SearchEngineSettingsView(model: model, scheme: scheme)
                            } label: {
                                HStack {
                                    Label {
                                        Text("settings.search_engine")
                                            .font(.system(size: 16, weight: .medium, design: .rounded))
                                    } icon: {
                                        Image(systemName: "magnifyingglass")
                                            .font(.system(size: 15, weight: .semibold))
                                            .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                                    }
                                    .foregroundStyle(Palette.ink(scheme))

                                    Spacer()

                                    Text(model.selectedSearchEngine.displayName)
                                        .font(.system(size: 15.5, design: .rounded))
                                        .foregroundStyle(Palette.ink(scheme).opacity(0.65))

                                    Image(systemName: "chevron.right")
                                        .font(.system(size: 13, weight: .semibold))
                                        .foregroundStyle(Palette.ink(scheme).opacity(0.3))
                                }
                                .padding(.horizontal, 16)
                                .frame(minHeight: 54)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)

                            Rectangle()
                                .fill(Palette.ink(scheme).opacity(0.15))
                                .frame(height: 0.5)
                                .padding(.leading, 44)

                            Toggle(isOn: $model.alwaysOpenExternally) {
                                Label {
                                    Text("settings.external_browser")
                                        .font(.system(size: 16, weight: .medium, design: .rounded))
                                } icon: {
                                    Image(systemName: "safari")
                                        .font(.system(size: 15, weight: .semibold))
                                        .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                                }
                                .foregroundStyle(Palette.ink(scheme))
                            }
                            .tint(Palette.coral(scheme))
                            .padding(.horizontal, 16)
                            .frame(minHeight: 54)
                        }
                        .background(Palette.lift(scheme), in: RoundedRectangle(cornerRadius: 16, style: .continuous))

                        if normalTabsCapability != .absent {
                            VStack(spacing: 0) {
                                HStack {
                                    Label {
                                        Text("settings.save_kind")
                                            .font(.system(size: 16, weight: .medium, design: .rounded))
                                    } icon: {
                                        Image(systemName: "pin")
                                            .font(.system(size: 15, weight: .semibold))
                                            .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                                    }
                                    .foregroundStyle(Palette.ink(scheme))

                                    Spacer()

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
                                        HStack(spacing: 4) {
                                            Text(displayedSaveKind.title)
                                                .font(.system(size: 15.5, design: .rounded))
                                            Image(systemName: "chevron.up.chevron.down")
                                                .font(.system(size: 10, weight: .semibold))
                                        }
                                        .foregroundStyle(Palette.ink(scheme).opacity(0.65))
                                    }
                                }
                                .padding(.horizontal, 16)
                                .frame(minHeight: 54)
                            }
                            .background(Palette.lift(scheme), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .padding(.top, 8)

                            if normalTabsCapability == .disabled {
                                Text("settings.save_kind.normal_disabled_caption")
                                    .font(.system(size: 12, design: .rounded))
                                    .foregroundStyle(Palette.ink(scheme).opacity(0.45))
                                    .padding(.horizontal, 4)
                            }
                        }

                        // Action bar placement: above the essentials grid or
                        // below the space switcher. Own card, under
                        // "Save shared tabs as".
                        VStack(spacing: 0) {
                            HStack {
                                Label {
                                    Text("settings.toolbar")
                                        .font(.system(size: 16, weight: .medium, design: .rounded))
                                } icon: {
                                    Image(systemName: "arrow.up.and.down")
                                        .font(.system(size: 15, weight: .semibold))
                                        .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                                }
                                .foregroundStyle(Palette.ink(scheme))

                                Spacer()

                                Menu {
                                    ForEach(ToolbarPlacement.allCases) { placement in
                                        Button {
                                            model.toolbarPlacement = placement
                                        } label: {
                                            if model.toolbarPlacement == placement {
                                                Label(placement.title, systemImage: "checkmark")
                                            } else {
                                                Text(placement.title)
                                            }
                                        }
                                    }
                                } label: {
                                    HStack(spacing: 4) {
                                        Text(model.toolbarPlacement.title)
                                            .font(.system(size: 15.5, design: .rounded))
                                        Image(systemName: "chevron.up.chevron.down")
                                            .font(.system(size: 10, weight: .semibold))
                                    }
                                    .foregroundStyle(Palette.ink(scheme).opacity(0.65))
                                }
                            }
                            .padding(.horizontal, 16)
                            .frame(minHeight: 54)
                        }
                        .background(Palette.lift(scheme), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .padding(.top, 8)

                        VStack(spacing: 0) {
                            NavigationLink {
                                AdvancedSettingsView(model: model, scheme: scheme)
                            } label: {
                                HStack {
                                    Label {
                                        Text("settings.advanced")
                                            .font(.system(size: 16, weight: .medium, design: .rounded))
                                    } icon: {
                                        Image(systemName: "gearshape")
                                            .font(.system(size: 15, weight: .semibold))
                                            .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                                    }
                                    .foregroundStyle(Palette.ink(scheme))

                                    Spacer()

                                    Image(systemName: "chevron.right")
                                        .font(.system(size: 13, weight: .semibold))
                                        .foregroundStyle(Palette.ink(scheme).opacity(0.3))
                                }
                                .padding(.horizontal, 16)
                                .frame(minHeight: 54)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                        .background(Palette.lift(scheme), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .padding(.top, 8)
                    }

                    // MARK: - 3. Feedback
                    VStack(alignment: .leading, spacing: 8) {
                        Text("FEEDBACK")
                            .font(.system(size: 12, weight: .semibold, design: .rounded))
                            .tracking(0.8)
                            .foregroundStyle(Palette.ink(scheme).opacity(0.4))
                            .padding(.horizontal, 4)

                        VStack(spacing: 0) {
                            Link(destination: Legal.feedbackURL) {
                                HStack {
                                    Label {
                                        Text("legal.feedback")
                                            .font(.system(size: 16, weight: .medium, design: .rounded))
                                    } icon: {
                                        Image(systemName: "bubble.left.and.text.bubble.right")
                                            .font(.system(size: 15, weight: .semibold))
                                            .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                                    }
                                    Spacer(minLength: 8)
                                    Image(systemName: "arrow.up.right")
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundStyle(Palette.ink(scheme).opacity(0.3))
                                }
                                .foregroundStyle(Palette.ink(scheme))
                                .padding(.horizontal, 16)
                                .frame(minHeight: 52)
                                .contentShape(Rectangle())
                            }

                            Rectangle()
                                .fill(Palette.ink(scheme).opacity(0.15))
                                .frame(height: 0.5)
                                .padding(.leading, 44)

                            Link(destination: Legal.writeReviewURL) {
                                HStack {
                                    Label {
                                        Text("legal.write_review")
                                            .font(.system(size: 16, weight: .medium, design: .rounded))
                                    } icon: {
                                        Image(systemName: "star")
                                            .font(.system(size: 15, weight: .semibold))
                                            .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                                    }
                                    Spacer(minLength: 8)
                                }
                                .foregroundStyle(Palette.ink(scheme))
                                .padding(.horizontal, 16)
                                .frame(minHeight: 52)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                        .background(Palette.lift(scheme), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }

                    // MARK: - 4. About & Links
                    VStack(alignment: .leading, spacing: 8) {
                        Text("ABOUT")
                            .font(.system(size: 12, weight: .semibold, design: .rounded))
                            .tracking(0.8)
                            .foregroundStyle(Palette.ink(scheme).opacity(0.4))
                            .padding(.horizontal, 4)

                        VStack(spacing: 0) {
                            Link(destination: Legal.publicPolicyURL) {
                                HStack {
                                    Label {
                                        Text("legal.privacy")
                                            .font(.system(size: 16, weight: .medium, design: .rounded))
                                    } icon: {
                                        Image(systemName: "hand.raised")
                                            .font(.system(size: 15, weight: .semibold))
                                            .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                                    }
                                    Spacer(minLength: 8)
                                    Image(systemName: "arrow.up.right")
                                        .font(.system(size: 12, weight: .semibold))
                                        .foregroundStyle(Palette.ink(scheme).opacity(0.3))
                                }
                                .foregroundStyle(Palette.ink(scheme))
                                .padding(.horizontal, 16)
                                .frame(minHeight: 52)
                                .contentShape(Rectangle())
                            }

                            Rectangle()
                                .fill(Palette.ink(scheme).opacity(0.15))
                                .frame(height: 0.5)
                                .padding(.leading, 44)

                            HStack {
                                Label {
                                    Text("app.name")
                                        .font(.system(size: 16, weight: .medium, design: .rounded))
                                } icon: {
                                    Image(systemName: "info.circle")
                                        .font(.system(size: 15, weight: .semibold))
                                        .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                                }
                                .foregroundStyle(Palette.ink(scheme))

                                Spacer(minLength: 8)

                                Text(appVersionString)
                                    .font(.system(size: 14, design: .rounded))
                                    .foregroundStyle(Palette.ink(scheme).opacity(0.4))
                            }
                            .padding(.horizontal, 16)
                            .frame(minHeight: 52)
                        }
                        .background(Palette.lift(scheme), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }

                    // MARK: - 3. Footer Attributions & Disclaimer
                    VStack(spacing: 6) {
                        Text("home.license_attribution")
                            .font(.system(size: 11, weight: .regular, design: .rounded))
                            .foregroundStyle(Palette.ink(scheme).opacity(0.35))

                        Text("home.disclaimer")
                            .font(.system(size: 10.5, weight: .regular, design: .rounded))
                            .foregroundStyle(Palette.ink(scheme).opacity(0.28))
                    }
                    .multilineTextAlignment(.center)
                    .padding(.top, 6)
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)
                .padding(.bottom, 32)
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
        }
    }
}

// MARK: - Search engine

/// Selection and management of search engines. Custom engines are added on
/// their own page (`AddSearchEngineView`), reached from the "Add custom search
/// engine" row.
private struct SearchEngineSettingsView: View {
    @Bindable var model: SettingsModel
    let scheme: ColorScheme

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                enginesCard
                addRow
            }
            .padding(.horizontal, 20)
            .padding(.top, 16)
            .padding(.bottom, 32)
        }
        .background(Palette.paper(scheme).ignoresSafeArea())
        .navigationTitle(Text("settings.search_engine"))
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: Engine list

    private var enginesCard: some View {
        let engines = model.availableSearchEngines
        return VStack(spacing: 0) {
            ForEach(Array(engines.enumerated()), id: \.element.id) { index, engine in
                engineRow(engine)
                if index < engines.count - 1 {
                    Rectangle()
                        .fill(Palette.ink(scheme).opacity(0.15))
                        .frame(height: 0.5)
                        .padding(.leading, 16)
                }
            }
        }
        .background(Palette.lift(scheme), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private func engineRow(_ engine: SearchEngine) -> some View {
        HStack(spacing: 12) {
            Button {
                model.selectedSearchEngine = engine
            } label: {
                HStack(spacing: 12) {
                    Text(engine.displayName)
                        .font(.system(size: 16, weight: .medium, design: .rounded))
                        .foregroundStyle(Palette.ink(scheme))
                    Spacer(minLength: 8)
                    if model.selectedSearchEngine.id == engine.id {
                        Image(systemName: "checkmark")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Palette.coral(scheme))
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if !engine.isBuiltIn {
                Button(role: .destructive) {
                    model.deleteCustomSearchEngine(engine)
                } label: {
                    Image(systemName: "trash")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color.red.opacity(0.8))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("Delete"))
            }
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 54)
    }

    // MARK: Add button

    private var addRow: some View {
        NavigationLink {
            AddSearchEngineView(model: model, scheme: scheme)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "plus")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Palette.coral(scheme))
                Text("Add custom search engine")
                    .font(.system(size: 16, weight: .medium, design: .rounded))
                    .foregroundStyle(Palette.ink(scheme))
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.3))
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 54)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(Palette.lift(scheme), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

}

// MARK: - Add custom search engine

/// Own page for adding a user-defined engine. A custom engine is just a URL
/// template with a `{query}` placeholder; the "paste a search link" shortcut
/// derives that template from a real search URL. On success the new engine is
/// selected and this page pops back to the engine list.
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
        .background(Palette.lift(scheme), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
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
        .background(Palette.lift(scheme), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
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
        .buttonStyle(.plain)
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

// MARK: - Advanced

/// Rarely touched settings live here instead of the main page. Currently the
/// essentials grouping override (SPEC §7.4): most users never need it because
/// `.automatic` resolves the effective grouping on its own.
private struct AdvancedSettingsView: View {
    @Bindable var model: SettingsModel
    let scheme: ColorScheme

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                VStack(spacing: 0) {
                    HStack {
                        Label {
                            Text("settings.essentials_grouping")
                                .font(.system(size: 16, weight: .medium, design: .rounded))
                        } icon: {
                            Image(systemName: "square.grid.2x2")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                        }
                        .foregroundStyle(Palette.ink(scheme))

                        Spacer()

                        Picker("settings.essentials_grouping", selection: $model.essentialsGrouping) {
                            ForEach(EssentialsGrouping.allCases) { grouping in
                                Text(grouping.titleKey).tag(grouping)
                            }
                        }
                        .pickerStyle(.menu)
                        .tint(Palette.ink(scheme).opacity(0.65))
                        .font(.system(size: 15.5, design: .rounded))
                    }
                    .padding(.horizontal, 16)
                    .frame(minHeight: 54)
                }
                .background(Palette.lift(scheme), in: RoundedRectangle(cornerRadius: 16, style: .continuous))

                Text("settings.essentials_grouping.caption")
                    .font(.system(size: 12, design: .rounded))
                    .foregroundStyle(Palette.ink(scheme).opacity(0.45))
                    .padding(.horizontal, 4)
            }
            .padding(.horizontal, 20)
            .padding(.top, 16)
            .padding(.bottom, 32)
        }
        .background(Palette.paper(scheme).ignoresSafeArea())
        .navigationTitle(Text("settings.advanced"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

private extension EssentialsGrouping {
    var titleKey: LocalizedStringKey {
        switch self {
        case .automatic: return "settings.essentials_grouping.automatic"
        case .containerSpecific: return "settings.essentials_grouping.container_specific"
        case .shared: return "settings.essentials_grouping.shared"
        }
    }
}
