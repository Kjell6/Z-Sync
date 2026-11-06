import SwiftUI

/// The pages opened in this browser, newest first and grouped by day.
struct BrowserHistoryView: View {
    /// Opens the picked page in the page the sheet came from.
    var onOpen: (BrowserVisit) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    @State private var query = ""
    @State private var confirmClear = false

    private var store: BrowserHistoryStore { .shared }

    private var days: [(day: Date, visits: [BrowserVisit])] {
        let calendar = Calendar.current
        let grouped = Dictionary(grouping: store.search(query)) { calendar.startOfDay(for: $0.lastVisited) }
        return grouped.keys.sorted(by: >).map { ($0, grouped[$0] ?? []) }
    }

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(Text("history.title"))
                .navigationBarTitleDisplayMode(.inline)
                .searchable(text: $query, prompt: Text("history.search"))
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("history.clear", role: .destructive) { confirmClear = true }
                            .disabled(store.visits.isEmpty)
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        ToolbarIconButton(systemName: "xmark", accessibilityKey: "common.done") {
                            dismiss()
                        }
                    }
                }
                .confirmationDialog(
                    "history.clear_title",
                    isPresented: $confirmClear,
                    titleVisibility: .visible
                ) {
                    Button("history.clear", role: .destructive) { store.removeAll() }
                    Button("common.cancel", role: .cancel) {}
                } message: {
                    Text("history.clear_message")
                }
        }
    }

    @ViewBuilder
    private var content: some View {
        let days = days
        if days.isEmpty {
            ContentUnavailableView {
                Label(
                    query.isEmpty ? "history.empty" : "history.no_results",
                    systemImage: query.isEmpty ? "clock" : "magnifyingglass"
                )
            }
        } else {
            List {
                ForEach(days, id: \.day) { group in
                    Section {
                        ForEach(group.visits) { visit in
                            row(visit)
                        }
                    } header: {
                        Text(group.day.formatted(date: .complete, time: .omitted))
                    }
                }
            }
            .listStyle(.plain)
        }
    }

    private func row(_ visit: BrowserVisit) -> some View {
        Button {
            onOpen(visit)
            dismiss()
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: visit.title.isEmpty ? visit.host : visit.title)
                    .zenFont(16, weight: .medium, design: .rounded)
                    .foregroundStyle(Palette.ink(scheme))
                    .lineLimit(1)
                Text(verbatim: "\(visit.lastVisited.formatted(date: .omitted, time: .shortened)) · \(visit.displayAddress)")
                    .zenFont(13, design: .rounded)
                    .foregroundStyle(Palette.ink(scheme).opacity(0.6))
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .swipeActions(edge: .trailing) {
            Button("history.delete", role: .destructive) { store.remove(visit) }
        }
    }
}
