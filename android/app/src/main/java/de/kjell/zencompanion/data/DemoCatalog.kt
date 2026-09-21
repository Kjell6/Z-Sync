package de.kjell.zencompanion.data

import de.kjell.zencompanion.sync.SyncedActivityService
import de.kjell.zencompanion.sync.ZenSpaces
import java.util.Date

/**
 * Bundled sample workspaces, tabs, and history for App Review demonstration
 * mode. Same screens as a live Mozilla account; no network to Firefox Sync.
 * Port of `Shared/DemoCatalog.swift`.
 */
object DemoCatalog {
    val account = AccountStore.AccountSnapshot(
        email = "sample@zencompanion.demo",
        uid = "demo",
        sessionTokenHex = "00".repeat(32),
        kBHex = "00".repeat(32),
        isDemo = true,
    )

    val snapshot: ZenSpaces.ZenSnapshot
        get() = ZenSpaces.ZenSnapshot(
            spaces = listOf(work, personal, reading),
            essentials = mapOf(
                "demo-container-work" to workEssentials,
                "demo-container-personal" to personalEssentials,
                "demo-container-reading" to readingEssentials,
            ),
            fetchedAtMillis = System.currentTimeMillis(),
            // The catalog contains normal tabs, so normal-tab syncing is on.
            normalTabsCapability = ZenSpaces.NormalTabsCapability.ENABLED,
        )

    val activity: SyncedActivityService.Activity
        get() = SyncedActivityService.Activity(history = history)

    private val work = ZenSpaces.ZenSpace(
        id = "demo-space-work",
        name = "Work",
        icon = "briefcase",
        containerGuid = "demo-container-work",
        theme = ZenSpaces.ZenSpaceTheme.fromGradientColors(listOf("#1B3A4B", "#2E6B6B"), opacity = 0.85),
        pinned = listOf(
            ZenSpaces.ZenItem.Tab(tab("demo-tab-github", "https://github.com", "GitHub")),
            ZenSpaces.ZenItem.Tab(tab("demo-tab-mdn", "https://developer.mozilla.org", "MDN Web Docs")),
            ZenSpaces.ZenItem.Split(
                ZenSpaces.ZenSplit(
                    id = "demo-split-docs",
                    gridType = "vsep",
                    tabs = listOf(
                        tab("demo-tab-html", "https://developer.mozilla.org/en-US/docs/Web/HTML", "HTML"),
                        tab("demo-tab-css", "https://developer.mozilla.org/en-US/docs/Web/CSS", "CSS"),
                    ),
                ),
            ),
            ZenSpaces.ZenItem.Folder(
                ZenSpaces.ZenFolder(
                    id = "demo-folder-tickets",
                    name = "Tickets",
                    icon = "ticket",
                    tabs = listOf(
                        tab("demo-tab-bugzilla", "https://bugzilla.mozilla.org", "Bugzilla"),
                        tab("demo-tab-issues", "https://example.com", "Project issues"),
                    ),
                ),
            ),
        ),
        tabs = listOf(
            ZenSpaces.ZenItem.Tab(tab("demo-normal-hn", "https://news.ycombinator.com", "Hacker News")),
            ZenSpaces.ZenItem.Split(
                ZenSpaces.ZenSplit(
                    id = "demo-normal-split",
                    gridType = "vsep",
                    tabs = listOf(
                        tab("demo-normal-blog", "https://example.com/blog", "Blog"),
                        tab("demo-normal-trending", "https://github.com/trending", "Trending on GitHub"),
                    ),
                ),
            ),
        ),
    )

    private val personal = ZenSpaces.ZenSpace(
        id = "demo-space-personal",
        name = "Personal",
        icon = "leaf",
        containerGuid = "demo-container-personal",
        theme = ZenSpaces.ZenSpaceTheme.fromGradientColors(listOf("#3D2B1F", "#C4785A"), opacity = 0.8),
        pinned = listOf(
            ZenSpaces.ZenItem.Tab(tab("demo-tab-mozilla", "https://www.mozilla.org", "Mozilla")),
            ZenSpaces.ZenItem.Tab(tab("demo-tab-wiki", "https://en.wikipedia.org/wiki/Firefox", "Firefox on Wikipedia")),
            ZenSpaces.ZenItem.Tab(tab("demo-tab-apple", "https://www.apple.com", "Apple")),
        ),
        tabs = listOf(
            ZenSpaces.ZenItem.Tab(tab("demo-normal-hn", "https://news.ycombinator.com", "Hacker News")),
            ZenSpaces.ZenItem.Tab(tab("demo-normal-start", "https://example.com/start", "Startpage")),
        ),
    )

    private val reading = ZenSpaces.ZenSpace(
        id = "demo-space-reading",
        name = "Reading",
        icon = "book",
        containerGuid = "demo-container-reading",
        theme = ZenSpaces.ZenSpaceTheme.fromGradientColors(listOf("#2C3E50", "#8E9AAF"), opacity = 0.82),
        pinned = listOf(
            ZenSpaces.ZenItem.Tab(tab("demo-tab-blog", "https://blog.mozilla.org", "Mozilla Blog")),
            ZenSpaces.ZenItem.Folder(
                ZenSpaces.ZenFolder(
                    id = "demo-folder-articles",
                    name = "Articles",
                    icon = "bookmark",
                    tabs = listOf(
                        tab("demo-tab-wiki-web", "https://en.wikipedia.org/wiki/World_Wide_Web", "World Wide Web"),
                        tab("demo-tab-common-mark", "https://commonmark.org", "CommonMark"),
                    ),
                    subfolders = listOf(
                        ZenSpaces.ZenFolder(
                            id = "demo-folder-specs",
                            name = "Specs",
                            icon = "page",
                            tabs = listOf(
                                tab("demo-tab-html-spec", "https://html.spec.whatwg.org", "HTML Living Standard"),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private val workEssentials = listOf(
        tab("demo-ess-mail", "https://www.mozilla.org/en-US/firefox/", "Firefox"),
        tab("demo-ess-cal", "https://www.wikipedia.org", "Wikipedia"),
    )

    private val personalEssentials = listOf(
        tab("demo-ess-personal-mozilla", "https://www.mozilla.org", "Mozilla"),
        tab("demo-ess-personal-apple", "https://www.apple.com", "Apple"),
    )

    private val readingEssentials = listOf(
        tab("demo-ess-reading-mdn", "https://developer.mozilla.org", "MDN"),
        tab("demo-ess-reading-github", "https://github.com", "GitHub"),
    )

    private val history: List<SyncedActivityService.HistoryEntry>
        get() = listOf(
            SyncedActivityService.HistoryEntry(
                title = "Mozilla",
                url = "https://www.mozilla.org",
                lastVisit = Date(System.currentTimeMillis() - 2 * 3_600_000L),
            ),
            SyncedActivityService.HistoryEntry(
                title = "Firefox on Wikipedia",
                url = "https://en.wikipedia.org/wiki/Firefox",
                lastVisit = Date(System.currentTimeMillis() - 5 * 3_600_000L),
            ),
            SyncedActivityService.HistoryEntry(
                title = "Apple",
                url = "https://www.apple.com",
                lastVisit = Date(System.currentTimeMillis() - 26 * 3_600_000L),
            ),
        )

    private fun tab(id: String, url: String, title: String) =
        ZenSpaces.ZenTab(id = id, url = url, title = title)
}
