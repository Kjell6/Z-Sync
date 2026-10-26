package de.kjell.zencompanion.data

/**
 * The domain rules of one EasyList-format filter list (EasyList for ads,
 * EasyPrivacy for trackers). Only rules that name a whole domain are kept:
 * `||ads.example^`, optionally limited to third-party requests, plus the
 * exceptions that undo them. Everything else in the list (paths, page element
 * hiding, rules for one site only) is skipped.
 *
 * Mirrored on iOS in `BlockList.swift`; both read the same files.
 */
class BlockList(
    /** Blocked wherever they load. */
    val anywhere: Set<String>,
    /** Blocked only when loaded by a page on another site. */
    val thirdParty: Set<String>,
    /** Exceptions: never blocked. */
    private val allowedEverywhere: Set<String>,
    /** Exceptions: never blocked when the page is on the same site. */
    private val allowedFirstParty: Set<String>,
    /** Exceptions: blocked domain to the sites where it is allowed. */
    private val allowedOn: Map<String, Set<String>>,
    /** Sites the list leaves alone entirely (`$document` exceptions). */
    private val allowedPages: Set<String>,
    /** The list's own version line, e.g. `202610242150`. */
    val version: String?,
    /** The list's "Last modified" line, e.g. `24 Oct 2026 21:50 UTC`. */
    val lastModified: String?,
) {
    val ruleCount: Int get() = anywhere.size + thirdParty.size

    /** True when a request to [requestHost] from a page on [pageHost] is blocked. */
    fun blocks(requestHost: String, pageHost: String?): Boolean {
        val request = requestHost.lowercase().trimEnd('.')
        val page = pageHost?.lowercase()?.trimEnd('.')
        if (page != null && matchesSuffix(page, allowedPages)) return false
        val thirdPartyRequest = page != null && !isSameSite(request, page)
        val listed = matchesSuffix(request, anywhere) ||
            (thirdPartyRequest && matchesSuffix(request, thirdParty))
        if (!listed) return false
        if (matchesSuffix(request, allowedEverywhere)) return false
        if (!thirdPartyRequest && matchesSuffix(request, allowedFirstParty)) return false
        if (page != null) {
            for (domain in suffixes(request)) {
                val sites = allowedOn[domain] ?: continue
                if (matchesSuffix(page, sites)) return false
            }
        }
        return true
    }

    companion object {
        /**
         * Request types a rule may be limited to. Both apps block a domain for
         * every type, so a block rule limited to some types is skipped, and an
         * exception limited to some types counts for all of them: either way
         * the app blocks less than the list, never more.
         */
        private val requestTypes = setOf(
            "script", "image", "stylesheet", "xmlhttprequest", "subdocument", "ping",
            "media", "font", "object", "other", "websocket",
        )
        private val domainRule = Regex("""^(@@)?\|\|([a-z0-9._-]+)\^(?:\$(.*))?$""")
        private val siteName = Regex("""^[a-z0-9._-]+$""")

        fun parse(text: String): BlockList {
            val anywhere = HashSet<String>()
            val thirdParty = HashSet<String>()
            val allowedEverywhere = HashSet<String>()
            val allowedFirstParty = HashSet<String>()
            val allowedOn = HashMap<String, MutableSet<String>>()
            val allowedPages = HashSet<String>()
            var version: String? = null
            var lastModified: String? = null

            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.startsWith("!")) {
                    if (version == null) version = line.headerValue("! Version:")
                    if (lastModified == null) lastModified = line.headerValue("! Last modified:")
                    return@forEach
                }
                val match = domainRule.matchEntire(line.lowercase()) ?: return@forEach
                val exception = match.groupValues[1].isNotEmpty()
                val domain = match.groupValues[2].trim('.')
                if (domain.isEmpty() || '.' !in domain) return@forEach
                val options = match.groupValues[3].split(',').map { it.trim() }.filter { it.isNotEmpty() }

                if (!exception) {
                    var onlyThirdParty = false
                    for (option in options) {
                        when (option) {
                            "third-party", "3p" -> onlyThirdParty = true
                            "all", "important" -> Unit
                            // Some request types only, popups, page blocks,
                            // one-site rules, redirects: not ours.
                            else -> return@forEach
                        }
                    }
                    (if (onlyThirdParty) thirdParty else anywhere).add(domain)
                    return@forEach
                }

                if (options.isEmpty()) {
                    allowedEverywhere.add(domain)
                    return@forEach
                }
                val sites = options.firstOrNull { it.startsWith("domain=") }
                    ?.removePrefix("domain=")
                    ?.split('|')
                    // Only plain names: iOS writes these into its rule list.
                    ?.filter { siteName.matches(it) }
                when {
                    sites != null -> if (sites.isNotEmpty()) allowedOn.getOrPut(domain) { HashSet() }.addAll(sites)
                    "document" in options -> allowedPages.add(domain)
                    "~third-party" in options || "1p" in options -> allowedFirstParty.add(domain)
                    options.any { it in requestTypes || it == "third-party" || it == "3p" } -> allowedEverywhere.add(domain)
                    // Element hiding and the like: nothing to undo here.
                    else -> Unit
                }
            }

            return BlockList(
                anywhere = anywhere,
                thirdParty = thirdParty,
                allowedEverywhere = allowedEverywhere,
                allowedFirstParty = allowedFirstParty,
                allowedOn = allowedOn,
                allowedPages = allowedPages,
                version = version,
                lastModified = lastModified,
            )
        }

        private fun String.headerValue(prefix: String): String? =
            if (startsWith(prefix, ignoreCase = true)) substring(prefix.length).trim().ifEmpty { null } else null

        /** `a.b.example.com`, `b.example.com`, `example.com`: never the bare top-level domain. */
        internal fun suffixes(host: String): Sequence<String> = sequence {
            var rest = host
            while ('.' in rest) {
                yield(rest)
                rest = rest.substringAfter('.')
            }
        }

        private fun matchesSuffix(host: String, set: Set<String>): Boolean =
            set.isNotEmpty() && suffixes(host).any { it in set }

        /**
         * Whether two hosts belong to the same site, for third-party rules.
         *
         * shortcut: compares the last two labels, or three under a short
         * second-level label such as `co.uk`, instead of reading the Public
         * Suffix List. It can only treat two sites as one (a rule then
         * doesn't fire), never split one site in two. Upgrade if that misses
         * real trackers.
         */
        internal fun isSameSite(a: String, b: String): Boolean = siteOf(a) == siteOf(b)

        private fun siteOf(host: String): String {
            val labels = host.split('.')
            if (labels.size <= 2) return host
            val keep = if (labels.last().length == 2 && labels[labels.size - 2].length <= 3) 3 else 2
            return labels.takeLast(keep).joinToString(".")
        }
    }
}
