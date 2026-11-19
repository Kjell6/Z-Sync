import Foundation

/// "Request Desktop Site" for pages that size themselves to the screen. The
/// desktop user agent alone leaves `<meta name="viewport"
/// content="width=device-width">` in charge, so a responsive site still lays
/// out at phone width and shows its mobile version. Safari and Chrome lay such
/// pages out at a desktop width instead; this script does the same by
/// rewriting the page's viewport tags to that width, also when a script adds or
/// changes one later. Pages without a viewport tag already get WebKit's
/// desktop width.
///
/// Android port: `DesktopViewportScript` in `ui/screens/MiniBrowserWebView.kt`
/// builds the same script.
enum DesktopViewportScript {
    /// Safari's and Chrome's layout width for a desktop page on a phone.
    static let width = 980

    static let source = """
    (function () {
      // Only the top document: a frame is laid out inside its parent's width.
      try {
        if (window.self !== window.top) return;
      } catch (e) {
        return;
      }
      var wanted = 'width=\(width)';
      function fix() {
        var tags = document.querySelectorAll('meta[name="viewport" i]');
        for (var i = 0; i < tags.length; i++) {
          if (tags[i].getAttribute('content') !== wanted) tags[i].setAttribute('content', wanted);
        }
      }
      fix();
      new MutationObserver(function (records) {
        for (var i = 0; i < records.length; i++) {
          var record = records[i];
          if (record.type === 'attributes') {
            if (record.target.nodeName === 'META') return fix();
            continue;
          }
          for (var j = 0; j < record.addedNodes.length; j++) {
            if (record.addedNodes[j].nodeName === 'META') return fix();
          }
        }
      }).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name'] });
    })()
    """
}
