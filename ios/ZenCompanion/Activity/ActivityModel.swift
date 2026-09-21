import Foundation
import Observation

/// Load + filter owner for `SyncedActivitySheet`.
@MainActor
@Observable
final class ActivityModel {
    var activity: SyncedActivityService.Activity?
    var loading = false
    var loadError: String?
    var searchText = ""
    var filteredHistory: [SyncedActivityService.HistoryEntry] = []

    private let loader: ActivityLoading

    init(loader: ActivityLoading = AppServices.activityLoader) {
        self.loader = loader
    }

    func load() async {
        loading = activity == nil
        loadError = nil
        do {
            activity = try await loader.load()
        } catch {
            loadError = error.zenUserMessage
        }
        updateFiltered()
        loading = false
    }

    /// Filtering runs once per change, not per body evaluation.
    func updateFiltered() {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let entries = activity?.history ?? []
        if query.isEmpty {
            filteredHistory = entries
        } else {
            filteredHistory = entries.filter {
                $0.title.localizedCaseInsensitiveContains(query) || $0.url.localizedCaseInsensitiveContains(query)
            }
        }
    }
}
