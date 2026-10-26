package de.kjell.zencompanion.ui.browser

import org.json.JSONObject

/** One block of extracted article text, in document order. */
data class ReaderBlock(
    /** Lowercase source tag: "h1"..."h6", "p", "li", "blockquote", "pre". */
    val kind: String,
    val text: String,
)

/** A page lifted into a plain reading view. */
data class ReaderArticle(
    val title: String,
    val site: String,
    val byline: String,
    val blocks: List<ReaderBlock>,
)

/**
 * Finds the article on a page and returns it as text.
 *
 * The script is a line-for-line copy of the iOS one in
 * `ios/ZenCompanion/Browser/MiniBrowserModel.swift` (`ReaderExtractor.script`):
 * it runs in the page, so both platforms must extract the same blocks. Change
 * both copies together. The script runs inside
 * the page, so it stays self-contained and ES5-only: Android's WebView here
 * and iOS `ReaderExtractor` evaluate the same source.
 */
object ReaderExtractor {
    const val SCRIPT = """(function () {
  var BLOCK_SELECTOR = 'h1,h2,h3,h4,h5,h6,p,li,blockquote,pre';
  var NOISE = { NAV: 1, ASIDE: 1, FOOTER: 1, HEADER: 1, FORM: 1, BUTTON: 1, DIALOG: 1 };
  // ARIA landmarks that are chrome around an article, not the article.
  var NOISE_ROLES = { banner: 1, complementary: 1, navigation: 1, contentinfo: 1, search: 1, menu: 1, menubar: 1, dialog: 1, alertdialog: 1 };
  // Chrome headings that survive the noise filter but say nothing in a reader.
  var JUNK_HEADINGS = { contents: 1, 'table of contents': 1, 'inhaltsverzeichnis': 1, inhalt: 1, menu: 1, navigation: 1 };

  function textOf(el) {
    if (!el) return '';
    var raw = el.innerText;
    if (raw === undefined || raw === null) raw = el.textContent || '';
    return String(raw).replace(/\s+/g, ' ').trim();
  }

  function isVisible(el) {
    try {
      var s = window.getComputedStyle(el);
      if (!s) return true;
      if (s.display === 'none' || s.visibility === 'hidden') return false;
      if (parseFloat(s.opacity) === 0) return false;
    } catch (e) {}
    return true;
  }

  function role(el) {
    var explicit = el.getAttribute && el.getAttribute('role');
    return explicit ? String(explicit).toLowerCase() : '';
  }

  // Cookie walls, donation banners and newsletter pop-ups paint over the
  // article, and their text often outscores the real one. Everything
  // pinned to the viewport is chrome, never the story. Memoised per run:
  // thousands of candidates share the same ancestors, and
  // getComputedStyle is the expensive part of this pass.
  var overlayMemo = new Map();
  function isOverlay(el) {
    if (overlayMemo.has(el)) return overlayMemo.get(el);
    var pinned = false;
    try {
      var s = window.getComputedStyle(el);
      if (s && (s.position === 'fixed' || s.position === 'sticky')) pinned = true;
    } catch (e) {}
    if (!pinned && el !== document.body && el.parentElement) pinned = isOverlay(el.parentElement);
    overlayMemo.set(el, pinned);
    return pinned;
  }

  // Landmarks and overlays that are not the article: walking up from a
  // block (or a candidate container) tells us whether it belongs to one.
  function inNoiseChrome(el, stopAt) {
    var node = el;
    while (node && node !== stopAt) {
      if (NOISE_ROLES[role(node)]) return true;
      if (node.getAttribute && node.getAttribute('aria-hidden') === 'true') return true;
      node = node.parentElement;
    }
    return false;
  }

  function linkShare(el) {
    var total = textOf(el).length;
    if (total === 0) return 1;
    var links = el.querySelectorAll('a');
    var len = 0;
    for (var i = 0; i < links.length; i++) len += textOf(links[i]).length;
    return len / total;
  }

  function meta(selector) {
    var el = document.querySelector(selector);
    if (!el) return '';
    var value = el.content !== undefined && el.content !== null ? el.content : textOf(el);
    return String(value).replace(/\s+/g, ' ').trim();
  }

  var candidates = document.querySelectorAll('article, main, [role="main"], section, div, td');
  function collect(skipChrome) {
    var scored = [];
    for (var i = 0; i < candidates.length; i++) {
      var el = candidates[i];
      if (!isVisible(el)) continue;
      if (skipChrome && (isOverlay(el) || inNoiseChrome(el, document.body))) continue;
      var length = textOf(el).length;
      if (length < 300) continue;
      var share = linkShare(el);
      if (share > 0.6 && length < 2000) continue;
      scored.push({ el: el, length: length, score: length - 1.2 * length * share + 30 * el.querySelectorAll('p').length });
    }
    return scored;
  }

  // Overlay filtering is the usual answer, but a site that pins its whole
  // layout (an app shell) would then have no candidates at all: fall back
  // to the unfiltered scan rather than declaring the page unreadable.
  var scored = collect(true);
  if (scored.length === 0) scored = collect(false);
  if (scored.length === 0) return null;

  var longest = 0;
  for (var l = 0; l < scored.length; l++) {
    if (scored[l].length > longest) longest = scored[l].length;
  }

  var winner = scored[0];
  for (var s = 1; s < scored.length; s++) {
    if (scored[s].score > winner.score) winner = scored[s];
  }

  // The whole page usually wins on raw text. Prefer the most nested
  // container that still holds nearly all of it, so the article body wins
  // over the shell around it.
  var chosen = winner.el;
  var deepest = -1;
  for (var c = 0; c < scored.length; c++) {
    if (scored[c].length < longest * 0.85) continue;
    var depth = 0;
    var node = scored[c].el;
    while (node) { depth++; node = node.parentElement; }
    if (depth > deepest) { deepest = depth; chosen = scored[c].el; }
  }

  function insideNoise(el) {
    var p = el.parentElement;
    while (p && p !== chosen) {
      if (NOISE[(p.tagName || '').toUpperCase()]) return true;
      if (NOISE_ROLES[role(p)]) return true;
      if (p.getAttribute && p.getAttribute('aria-hidden') === 'true') return true;
      p = p.parentElement;
    }
    return false;
  }

  function insideBlock(el) {
    var p = el.parentElement;
    while (p && p !== chosen) {
      var tag = (p.tagName || '').toLowerCase();
      if (tag === 'li' || tag === 'blockquote' || tag === 'pre' || tag === 'figcaption') return true;
      p = p.parentElement;
    }
    return false;
  }

  var blocks = [];
  var nodes = chosen.querySelectorAll(BLOCK_SELECTOR);
  for (var b = 0; b < nodes.length; b++) {
    var node = nodes[b];
    if (!isVisible(node)) continue;
    if (insideNoise(node) || insideBlock(node)) continue;
    var text = textOf(node);
    if (text.length < 2) continue;
    if (linkShare(node) > 0.7) continue;
    var tag = node.tagName.toLowerCase();
    if (tag === 'h1' || tag === 'h2' || tag === 'h3') {
      if (JUNK_HEADINGS[text.toLowerCase()]) continue;
    }
    blocks.push({ kind: tag, text: text });
  }

  var merged = [];
  for (var m = 0; m < blocks.length; m++) {
    var previous = merged[merged.length - 1];
    if (previous && previous.text === blocks[m].text) continue;
    merged.push(blocks[m]);
  }
  if (merged.length === 0) return null;

  var title = meta('meta[property="og:title"]') || meta('meta[name="twitter:title"]');
  if (!title) {
    var heading = document.querySelector('h1');
    title = heading ? textOf(heading) : '';
  }
  if (!title) title = String(document.title || '').trim();

  var byline = meta('meta[name="author"]') || meta('meta[property="article:author"]');
  if (!byline) {
    var author = document.querySelector('[rel="author"], .author, .byline');
    if (author) byline = textOf(author).slice(0, 80);
  }

  var site = meta('meta[property="og:site_name"]') || location.hostname.replace(/^www\./, '');

  return { title: title, site: site, byline: byline, blocks: merged };
})()"""

    /** The script wrapped so the page hands back one JSON string. */
    val jsonScript: String get() = "JSON.stringify($SCRIPT)"

    /** Parses the page payload. Null means the page has no article to lift. */
    fun parse(payload: String?): ReaderArticle? {
        if (payload.isNullOrBlank()) return null
        val raw = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val array = raw.optJSONArray("blocks") ?: return null
        val blocks = buildList {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val text = entry.optString("text")
                if (text.isEmpty()) continue
                add(ReaderBlock(kind = entry.optString("kind", "p"), text = text))
            }
        }
        if (blocks.isEmpty()) return null
        return ReaderArticle(
            title = raw.optString("title"),
            site = raw.optString("site"),
            byline = raw.optString("byline"),
            blocks = blocks,
        )
    }
}
