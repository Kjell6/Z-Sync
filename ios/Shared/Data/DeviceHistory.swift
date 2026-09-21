import Foundation

// MARK: - Synced browsing history
//
// Read-only view over Firefox Sync's classic `history` collection: one
// encrypted record per visited URL, cleartext
// {"histUri", "title", "visits": [{date, type}]} with microseconds-since-
// epoch visit dates and optional WBO-level "deleted": true.
enum SyncedActivityService {
    // MARK: - Models

    struct HistoryEntry: Identifiable, Hashable {
        var id: String { url }
        let title: String
        let url: String
        var lastVisit: Date?
    }

    struct Activity: Equatable {
        var history: [HistoryEntry]
    }

    // MARK: - Load

    static func load() async throws -> Activity {
        if AccountStore.isDemo {
            return DemoCatalog.activity
        }
        let client = try await AccountStore.connect()
        return try await load(client: client)
    }

    static func load(client: SyncClient) async throws -> Activity {
        Activity(history: try await historyEntries(client: client))
    }

    // MARK: - Synced history (`history` collection)

    static func historyEntries(client: SyncClient, limit: Int = 400) async throws -> [HistoryEntry] {
        let records = try await client.getRecentRecords(collection: "history", limit: limit)
        var out: [HistoryEntry] = []
        for record in records {
            guard let id = record["id"] as? String, !id.isEmpty else { continue }
            guard let cleartext = try? await client.decryptRecord(collection: "history", record: record) else {
                continue
            }
            if (cleartext["deleted"] as? Bool) == true { continue }
            guard let url = cleartext["histUri"] as? String,
                  let scheme = URL(string: url)?.scheme?.lowercased(),
                  scheme == "http" || scheme == "https"
            else { continue }
            // Visit dates are microseconds since epoch (Places convention);
            // some clients send doubles or strings instead of integers.
            let lastVisit = (cleartext["visits"] as? [[String: Any]])?
                .compactMap { visit -> Double? in
                    if let ms = visit["date"] as? Double { return ms }
                    if let msInt = visit["date"] as? Int { return Double(msInt) }
                    if let msStr = visit["date"] as? String { return Double(msStr) }
                    return nil
                }
                .max()
                .map { Date(timeIntervalSince1970: $0 / 1_000_000) }
            out.append(HistoryEntry(
                title: (cleartext["title"] as? String) ?? "",
                url: url,
                lastVisit: lastVisit
            ))
        }
        out.sort { ($0.lastVisit?.timeIntervalSince1970 ?? 0) > ($1.lastVisit?.timeIntervalSince1970 ?? 0) }
        return out
    }
}
