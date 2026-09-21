package de.kjell.zencompanion.sync

import android.content.Context
import de.kjell.zencompanion.data.AccountStore
import de.kjell.zencompanion.data.DemoCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.Date

/**
 * Port of `Shared/DeviceHistory.swift`. Read-only view over Firefox Sync's
 * classic synced browsing history (`history`). Nothing here writes to sync.
 */
object SyncedActivityService {
    data class HistoryEntry(
        val title: String,
        val url: String,
        val lastVisit: Date?,
    )

    data class Activity(
        val history: List<HistoryEntry>,
    )

    suspend fun load(context: Context): Activity = withContext(Dispatchers.IO) {
        if (AccountStore.isDemo(context)) return@withContext DemoCatalog.activity
        val client = AccountStore.connect(context)
        load(client)
    }

    fun load(client: SyncClient): Activity {
        val history = historyEntries(client)
        return Activity(history = history)
    }

    // MARK: - Synced history (`history` collection)

    fun historyEntries(client: SyncClient, limit: Int = 400): List<HistoryEntry> {
        val records = client.getRecentRecords("history", limit)
        val out = mutableListOf<HistoryEntry>()
        for (record in records) {
            if (record.optString("id").isEmpty()) continue
            val cleartext = runCatching { client.decryptRecord("history", record) }.getOrNull() ?: continue
            if (cleartext.optBoolean("deleted")) continue
            val url = cleartext.optString("histUri")
            val scheme = runCatching { URL(url).protocol?.lowercase() }.getOrNull()
            if (scheme != "http" && scheme != "https") continue

            // Visit dates are microseconds since epoch (Places convention);
            // some clients send doubles or strings instead of integers.
            val visits = cleartext.optJSONArray("visits")
            var lastVisitUs = -1.0
            if (visits != null) {
                for (i in 0 until visits.length()) {
                    val visit = visits.optJSONObject(i) ?: continue
                    val us = when (val v = visit.opt("date")) {
                        is Double -> v
                        is Int -> v.toDouble()
                        is Long -> v.toDouble()
                        is String -> v.toDoubleOrNull() ?: continue
                        else -> continue
                    }
                    if (us > lastVisitUs) lastVisitUs = us
                }
            }
            out.add(
                HistoryEntry(
                    title = cleartext.optString("title"),
                    url = url,
                    lastVisit = if (lastVisitUs >= 0) Date((lastVisitUs / 1000).toLong()) else null,
                ),
            )
        }
        return out.sortedByDescending { it.lastVisit?.time ?: 0 }
    }
}
