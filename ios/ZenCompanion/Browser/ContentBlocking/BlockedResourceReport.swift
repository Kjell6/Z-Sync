import WebKit

/// The page script and message handler behind the blocked count on iOS.
/// WebKit blocks through the rule lists without saying what it blocked, so the
/// page reports the addresses of the resources it adds (scripts, images,
/// frames, styles, media) and the model counts the ones a list matches. It
/// misses resources that never appear in the page, so the count is close, not
/// exact. Only addresses cross the bridge, and only from the main frame. The
/// script and its handler live in the app's own content world, so the page's
/// scripts can see neither of them.
enum BlockedResourceReport {
    @MainActor static var world: WKContentWorld { .defaultClient }
    static let handlerName = "zsyncResources"

    static let script = """
    (function () {
      if (window.__zsyncResources) return;
      Object.defineProperty(window, '__zsyncResources', { value: true });
      var handler = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.\(handlerName);
      if (!handler) return;
      var selector = 'script[src],img[src],iframe[src],source[src],video[src],audio[src],embed[src],' +
        'link[rel~="stylesheet"][href],link[rel~="preload"][href],link[rel~="icon"][href]';
      var seen = {}, queue = [], timer = null, count = 0;
      function add(value) {
        if (!value || count >= 2000) return;
        var href;
        try { href = new URL(value, location.href).href; } catch (e) { return; }
        if (href.slice(0, 4) !== 'http' || seen[href]) return;
        seen[href] = true;
        count++;
        queue.push(href);
        if (!timer) timer = setTimeout(flush, 500);
      }
      function flush() {
        timer = null;
        if (queue.length) handler.postMessage({ urls: queue.splice(0, queue.length) });
      }
      function scan(node) {
        if (node.matches && node.matches(selector)) add(node.getAttribute('src') || node.getAttribute('href'));
        if (node.querySelectorAll) {
          node.querySelectorAll(selector).forEach(function (el) { add(el.getAttribute('src') || el.getAttribute('href')); });
        }
      }
      new MutationObserver(function (records) {
        records.forEach(function (record) {
          record.addedNodes.forEach(function (node) { if (node.nodeType === 1) scan(node); });
        });
      }).observe(document, { childList: true, subtree: true });
      if (document.documentElement) scan(document.documentElement);
    })();
    """

    /// Receives the page's reports. Holds the model weakly: the content
    /// controller keeps its handlers alive for as long as the web view lives.
    final class Handler: NSObject, WKScriptMessageHandler {
        weak var model: MiniBrowserModel?

        init(model: MiniBrowserModel) {
            self.model = model
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            MainActor.assumeIsolated {
                guard message.frameInfo.isMainFrame,
                      message.world == BlockedResourceReport.world,
                      let body = message.body as? [String: Any],
                      let urls = body["urls"] as? [String] else { return }
                // The page's address from WebKit, not from the message.
                model?.resourcesLoaded(Array(urls.prefix(2_000)), page: message.frameInfo.request.url)
            }
        }
    }
}
