import SwiftUI

/// Read-only view over Firefox Sync's classic synced browsing history
/// (`history` collection). Opening an entry runs the shared mini-browser;
/// nothing here writes to sync.
struct SyncedActivitySheet: View {
    let allSpaces: [ZenSpace]
    let scheme: ColorScheme
    var onOpenURL: (URL, ZenSpace) -> Void

    @State private var model = ActivityModel()

    var body: some View {
        @Bindable var model = model

        NavigationStack {
            Group {
                if let activity = model.activity {
                    content(activity)
                } else if model.loading {
                    VStack(spacing: 14) {
                        ProgressView()
                            .controlSize(.large)
                            .tint(Palette.coral(scheme))
                        Text("activity.connecting")
                            .font(.system(size: 15, weight: .medium, design: .rounded))
                            .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if let loadError = model.loadError {
                    errorArea(loadError)
                }
            }
            .background(Palette.paper(scheme).ignoresSafeArea())
            .navigationTitle(Text("activity.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    ToolbarIconButton(systemName: "xmark", accessibilityKey: "common.done") {
                        dismiss()
                    }
                }
            }
            .searchable(
                text: $model.searchText,
                placement: .navigationBarDrawer(displayMode: .automatic),
                prompt: Text("activity.search_prompt")
            )
            .onChange(of: model.searchText) { _, _ in model.updateFiltered() }
            .task {
                guard model.activity == nil else { return }
                await model.load()
            }
            .refreshable { await model.load() }
        }
    }

    @Environment(\.dismiss) private var dismiss

    // MARK: Content (card design, mirrors the Settings sheet style)

    private func content(_ activity: SyncedActivityService.Activity) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                if !model.filteredHistory.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        sectionLabel("activity.section.history")
                        Card(scheme: scheme) {
                            LazyVStack(alignment: .leading, spacing: 0) {
                                ForEach(model.filteredHistory, id: \.id) { entry in
                                    HistoryRow(entry: entry, scheme: scheme) {
                                        open(urlString: entry.url, title: entry.title)
                                    }
                                    if entry.id != model.filteredHistory.last?.id {
                                        Divider()
                                            .foregroundStyle(Palette.ink(scheme).opacity(0.15))
                                            .padding(.leading, 56)
                                    }
                                }
                            }
                        }
                        footerText("activity.history.footer")
                    }
                }

                if model.filteredHistory.isEmpty {
                    Text("activity.empty")
                        .font(.system(size: 14, design: .rounded))
                        .foregroundStyle(Palette.ink(scheme).opacity(0.5))
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.top, 60)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 6)
            .padding(.bottom, 32)
        }
    }

    private func sectionLabel(_ key: LocalizedStringKey) -> some View {
        Text(key)
            .font(.system(size: 12, weight: .semibold, design: .rounded))
            .tracking(0.8)
            .foregroundStyle(Palette.ink(scheme).opacity(0.4))
            .padding(.horizontal, 4)
    }

    private func footerText(_ key: LocalizedStringKey) -> some View {
        Text(key)
            .font(.system(size: 11.5, design: .rounded))
            .foregroundStyle(Palette.ink(scheme).opacity(0.35))
            .padding(.horizontal, 8)
    }

    /// Single rounded container matching the Settings sheet cards.
    private struct Card<Content: View>: View {
        let scheme: ColorScheme
        @ViewBuilder var content: () -> Content

        var body: some View {
            VStack(alignment: .leading, spacing: 0) {
                content()
            }
            .background(Palette.lift(scheme), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
    }

    // MARK: Actions

    private func open(urlString: String, title: String) {
        guard let url = URL(string: urlString) else { return }
        let space = allSpaces.first ?? ZenSpace.fallback
        onOpenURL(url, space)
    }

    @ViewBuilder
    private func errorArea(_ message: String) -> some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 30, weight: .medium))
                .foregroundStyle(Palette.ink(scheme).opacity(0.35))
            Text(message)
                .font(.system(size: 15, design: .rounded))
                .foregroundStyle(Palette.ink(scheme).opacity(0.7))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Button {
                Task { await model.load() }
            } label: {
                Label(String(localized: "spaces.retry"), systemImage: "arrow.clockwise")
                    .font(.system(size: 16, weight: .semibold, design: .rounded))
                    .padding(.horizontal, 20)
                    .padding(.vertical, 12)
                    .background(Palette.lift(scheme), in: Capsule())
                    .foregroundStyle(Palette.ink(scheme))
            }
            .buttonStyle(.plain)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - One history entry

private struct HistoryRow: View {
    let entry: SyncedActivityService.HistoryEntry
    let scheme: ColorScheme
    var onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            HStack(spacing: 12) {
                if FaviconResolver.isLocalURL(entry.url) {
                    Image(systemName: "gearshape.fill")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(Palette.ink(scheme).opacity(0.55))
                        .frame(width: 26, height: 26)
                } else {
                    Favicon(urlString: entry.url, size: 26)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(entry.title.isEmpty ? entry.url : entry.title)
                        .font(.system(size: 15.5, weight: .medium, design: .rounded))
                        .foregroundStyle(Palette.ink(scheme))
                        .lineLimit(1)
                    Text(secondaryText)
                        .font(.system(size: 12, design: .rounded))
                        .foregroundStyle(Palette.ink(scheme).opacity(0.4))
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityHint(Text(entry.url))
    }

    private var secondaryText: String {
        let host = URL(string: entry.url)?.host ?? ""
        if let visit = entry.lastVisit {
            return "\(host) · \(visit.formatted(.relative(presentation: .named)))"
        }
        return host
    }
}
