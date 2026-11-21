import SwiftUI

/// The browser's archive: when pages close by themselves, then every page
/// opened in this browser, newest first and grouped by day. One card per day,
/// app colours only, and the list's own swipe to delete an entry.
struct BrowserHistoryView: View {
    /// Opens the picked page: in the page behind the sheet, or in a new one
    /// when the overview came up without a page.
    var onOpen: (BrowserVisit) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @State private var query = ""
    /// The same choice as Settings › Pages & tabs, so it can be changed where
    /// its effect shows up.
    @State private var lifetime = KeptPageLifetime.stored(in: AppServices.preferences)

    private var store: BrowserHistoryStore { .shared }

    private var days: [(day: Date, visits: [BrowserVisit])] {
        let calendar = Calendar.current
        let grouped = Dictionary(grouping: store.search(query)) { calendar.startOfDay(for: $0.lastVisited) }
        return grouped.keys.sorted(by: >).map { ($0, grouped[$0] ?? []) }
    }

    var body: some View {
        NavigationStack {
            content
                .background(Palette.background(scheme).ignoresSafeArea())
                .navigationTitle(Text("history.title"))
                .navigationBarTitleDisplayMode(.inline)
                .searchable(
                    text: $query,
                    placement: .navigationBarDrawer(displayMode: .automatic),
                    prompt: Text("history.search")
                )
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        ToolbarIconButton(systemName: "xmark", accessibilityKey: "common.done") {
                            dismiss()
                        }
                    }
                }
        }
    }

    // A List, not a stack of cards: the swipe to delete an entry is the
    // system's own, and only a List has it.
    private var content: some View {
        let days = days
        return List {
            // Settings, not results: searching hides it.
            if query.isEmpty {
                Section {
                    lifetimeRow
                } footer: {
                    Text("history.archive_footer")
                        .zenFont(12.5, design: .rounded)
                        .foregroundStyle(Palette.ink(scheme).opacity(0.45))
                }
            }

            if days.isEmpty {
                Section {
                    emptyState
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                } header: {
                    if query.isEmpty { historyHeading }
                }
            } else {
                ForEach(days, id: \.day) { group in
                    Section {
                        ForEach(group.visits) { visit in
                            row(visit)
                        }
                    } header: {
                        // The setting above and the pages below are two
                        // things; the first day carries the list's own title.
                        if query.isEmpty, group.day == days.first?.day {
                            VStack(alignment: .leading, spacing: 10) {
                                historyHeading
                                dayLabel(group.day)
                            }
                        } else {
                            dayLabel(group.day)
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .scrollDismissesKeyboard(.immediately)
    }

    /// When unused pages close and land here.
    private var lifetimeRow: some View {
        HStack(spacing: 12) {
            Text("settings.kept_lifetime")
                .zenFont(15.5, weight: .medium, design: .rounded)
                .foregroundStyle(Palette.ink(scheme))
            Spacer(minLength: 8)
            Menu {
                ForEach(KeptPageLifetime.allCases) { option in
                    Button {
                        lifetime = option
                    } label: {
                        if lifetime == option {
                            Label(option.title, systemImage: "checkmark")
                        } else {
                            Text(option.title)
                        }
                    }
                }
            } label: {
                HStack(spacing: 4) {
                    Text(lifetime.title)
                        .zenFont(15.5, design: .rounded)
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.system(size: 10, weight: .semibold))
                }
                .foregroundStyle(Palette.ink(scheme).opacity(0.65))
            }
        }
        .padding(.vertical, 3)
        .listRowBackground(Palette.lift(scheme))
        .onChange(of: lifetime) { _, value in
            AppServices.preferences.setString(value.rawValue, PreferenceKeys.keptPageLifetime, scope: .standard)
        }
    }

    private var historyHeading: some View {
        Text("history.section")
            .zenFont(20, weight: .semibold, design: .rounded)
            .foregroundStyle(Palette.ink(scheme))
            .textCase(nil)
            .padding(.top, 14)
            .accessibilityAddTraits(.isHeader)
    }

    private func dayLabel(_ day: Date) -> some View {
        Text(verbatim: day.formatted(date: .complete, time: .omitted))
            .zenFont(12, weight: .semibold, design: .rounded)
            .tracking(0.8)
            .foregroundStyle(Palette.ink(scheme).opacity(0.4))
            .textCase(nil)
    }

    private func row(_ visit: BrowserVisit) -> some View {
        Button {
            onOpen(visit)
            dismiss()
        } label: {
            HStack(spacing: 12) {
                Favicon(urlString: visit.url, size: 26)
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: visit.title.isEmpty ? visit.host : visit.title)
                        .zenFont(15.5, weight: .medium, design: .rounded)
                        .foregroundStyle(Palette.ink(scheme))
                        .lineLimit(1)
                    Text(verbatim: secondaryText(visit))
                        .zenFont(12, design: .rounded)
                        .foregroundStyle(Palette.ink(scheme).opacity(0.4))
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(.vertical, 3)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressedButtonStyle())
        .listRowBackground(Palette.lift(scheme))
        .listRowSeparatorTint(Palette.ink(scheme).opacity(0.12))
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button(role: .destructive) {
                store.remove(visit)
            } label: {
                Label {
                    Text("history.delete")
                } icon: {
                    Image(systemName: "trash")
                }
            }
        }
        .accessibilityHint(Text(verbatim: visit.url))
    }

    private func secondaryText(_ visit: BrowserVisit) -> String {
        let time = visit.lastVisited.formatted(date: .omitted, time: .shortened)
        return "\(time) · \(visit.displayAddress)"
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: query.isEmpty ? "archivebox" : "magnifyingglass")
                .font(.system(size: 30, weight: .medium))
                .foregroundStyle(Palette.ink(scheme).opacity(0.3))
            Text(query.isEmpty ? "history.empty" : "history.no_results")
                .zenFont(15, weight: .medium, design: .rounded)
                .foregroundStyle(Palette.ink(scheme).opacity(0.55))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 48)
    }
}
