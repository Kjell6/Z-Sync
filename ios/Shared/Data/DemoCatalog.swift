import Foundation

/// Bundled sample workspaces, tabs, and history for App Review demonstration
/// mode. Same screens as a live Mozilla account; no network to Firefox Sync.
enum DemoCatalog {
    static let account = AccountSnapshot(
        email: "sample@zencompanion.demo",
        uid: "demo",
        sessionTokenHex: String(repeating: "00", count: 32),
        kBHex: String(repeating: "00", count: 32),
        isDemo: true
    )

    static var snapshot: ZenSnapshot {
        ZenSnapshot(
            spaces: [work, personal, reading],
            essentials: [
                "demo-container-work": workEssentials,
                "demo-container-personal": personalEssentials,
                "demo-container-reading": readingEssentials
            ],
            fetchedAt: Date()
        )
    }

    static var activity: SyncedActivityService.Activity {
        SyncedActivityService.Activity(history: history)
    }

    // MARK: Spaces

    private static let work = ZenSpace(
        id: "demo-space-work",
        name: "Work",
        icon: "briefcase",
        containerGuid: "demo-container-work",
        theme: ZenSpaceTheme(gradientColors: ["#1B3A4B", "#2E6B6B"], opacity: 0.85),
        pinned: [
            .tab(tab("demo-tab-github", "https://github.com", "GitHub")),
            .tab(tab("demo-tab-mdn", "https://developer.mozilla.org", "MDN Web Docs")),
            .split(ZenSplit(
                id: "demo-split-docs",
                gridType: "vsep",
                tabs: [
                    tab("demo-tab-html", "https://developer.mozilla.org/en-US/docs/Web/HTML", "HTML"),
                    tab("demo-tab-css", "https://developer.mozilla.org/en-US/docs/Web/CSS", "CSS")
                ]
            )),
            .folder(ZenFolder(
                id: "demo-folder-tickets",
                name: "Tickets",
                icon: "ticket",
                tabs: [
                    tab("demo-tab-bugzilla", "https://bugzilla.mozilla.org", "Bugzilla"),
                    tab("demo-tab-issues", "https://example.com", "Project issues")
                ]
            ))
        ],
        tabs: [
            .tab(tab("demo-normal-hn", "https://news.ycombinator.com", "Hacker News")),
            .split(ZenSplit(
                id: "demo-normal-split",
                gridType: "vsep",
                tabs: [
                    tab("demo-normal-blog", "https://example.com/blog", "Blog"),
                    tab("demo-normal-trending", "https://github.com/trending", "Trending on GitHub")
                ]
            ))
        ]
    )

    private static let personal = ZenSpace(
        id: "demo-space-personal",
        name: "Personal",
        icon: "leaf",
        containerGuid: "demo-container-personal",
        theme: ZenSpaceTheme(gradientColors: ["#3D2B1F", "#C4785A"], opacity: 0.8),
        pinned: [
            .tab(tab("demo-tab-mozilla", "https://www.mozilla.org", "Mozilla")),
            .tab(tab("demo-tab-wiki", "https://en.wikipedia.org/wiki/Firefox", "Firefox on Wikipedia")),
            .tab(tab("demo-tab-apple", "https://www.apple.com", "Apple"))
        ],
        tabs: [
            .tab(tab("demo-normal-hn", "https://news.ycombinator.com", "Hacker News")),
            .tab(tab("demo-normal-start", "https://example.com/start", "Startpage"))
        ]
    )

    private static let reading = ZenSpace(
        id: "demo-space-reading",
        name: "Reading",
        icon: "book",
        containerGuid: "demo-container-reading",
        theme: ZenSpaceTheme(gradientColors: ["#2C3E50", "#8E9AAF"], opacity: 0.82),
        pinned: [
            .tab(tab("demo-tab-blog", "https://blog.mozilla.org", "Mozilla Blog")),
            .folder(ZenFolder(
                id: "demo-folder-articles",
                name: "Articles",
                icon: "bookmark",
                tabs: [
                    tab("demo-tab-wiki-web", "https://en.wikipedia.org/wiki/World_Wide_Web", "World Wide Web"),
                    tab("demo-tab-common-mark", "https://commonmark.org", "CommonMark")
                ],
                subfolders: [
                    ZenFolder(
                        id: "demo-folder-specs",
                        name: "Specs",
                        icon: "page",
                        tabs: [
                            tab("demo-tab-html-spec", "https://html.spec.whatwg.org", "HTML Living Standard")
                        ]
                    )
                ]
            ))
        ]
    )

    private static let workEssentials: [ZenTab] = [
        tab("demo-ess-mail", "https://www.mozilla.org/en-US/firefox/", "Firefox"),
        tab("demo-ess-cal", "https://www.wikipedia.org", "Wikipedia")
    ]

    private static let personalEssentials: [ZenTab] = [
        tab("demo-ess-personal-mozilla", "https://www.mozilla.org", "Mozilla"),
        tab("demo-ess-personal-apple", "https://www.apple.com", "Apple")
    ]

    private static let readingEssentials: [ZenTab] = [
        tab("demo-ess-reading-mdn", "https://developer.mozilla.org", "MDN"),
        tab("demo-ess-reading-github", "https://github.com", "GitHub")
    ]

    // MARK: Activity

    private static let history: [SyncedActivityService.HistoryEntry] = [
        SyncedActivityService.HistoryEntry(
            title: "Mozilla",
            url: "https://www.mozilla.org",
            lastVisit: Date().addingTimeInterval(-2 * 3600)
        ),
        SyncedActivityService.HistoryEntry(
            title: "Firefox on Wikipedia",
            url: "https://en.wikipedia.org/wiki/Firefox",
            lastVisit: Date().addingTimeInterval(-5 * 3600)
        ),
        SyncedActivityService.HistoryEntry(
            title: "Apple",
            url: "https://www.apple.com",
            lastVisit: Date().addingTimeInterval(-26 * 3600)
        )
    ]

    private static func tab(_ id: String, _ url: String, _ title: String) -> ZenTab {
        ZenTab(id: id, url: url, title: title)
    }
}
