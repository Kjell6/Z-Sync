import SwiftUI
import WebKit

struct FxAWebView: UIViewRepresentable {
    var onLogin: (FxAWebLogin) -> Void
    var onUnverified: () -> Void
    var onError: (String) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onLogin: onLogin, onUnverified: onUnverified, onError: onError)
    }

    func makeUIView(context: Context) -> WKWebView {
        let content = WKUserContentController()
        content.removeScriptMessageHandler(forName: "fxa")
        content.add(context.coordinator, name: "fxa")
        content.addUserScript(WKUserScript(
            source: Self.bridgeJS,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true
        ))
        let config = WKWebViewConfiguration()
        config.userContentController = content
        config.defaultWebpagePreferences.allowsContentJavaScript = true
        config.websiteDataStore = WKWebsiteDataStore.nonPersistent()
        let web = WKWebView(frame: .zero, configuration: config)
        web.customUserAgent = Self.firefoxDesktopUA
        web.navigationDelegate = context.coordinator
        #if DEBUG
        if #available(iOS 16.4, *) {
            web.isInspectable = true
        }
        #endif
        context.coordinator.attachAndLoad(web)
        return web
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        context.coordinator.onLogin = onLogin
        context.coordinator.onUnverified = onUnverified
        context.coordinator.onError = onError
    }

    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        uiView.configuration.userContentController.removeScriptMessageHandler(forName: "fxa")
    }

    static let firefoxDesktopUA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:140.0) Gecko/20100101 Firefox/140.0"

    /// WebKit content blocker (not page JS). Page scripts never reliably hid
    /// Mozilla's Apple/Google buttons; css-display-none is applied by WebKit
    /// itself and ignores the site CSP. The passkey button is matched by its
    /// icon path because it carries no stable label; the bridge JS removes it
    /// anyway (this just avoids a first-paint flash on modern iOS).
    static let socialHideRulesJSON = """
    [
      {
        "trigger": {
          "url-filter": ".*",
          "if-domain": ["*accounts.firefox.com"]
        },
        "action": {
          "type": "css-display-none",
          "selector": "button[aria-label*='Apple'], button[aria-label*='Google'], button[aria-label*='apple'], button[aria-label*='google'], div:has(> button[aria-label*='Apple']), div:has(> button[aria-label*='Google']), button:has(path[d^='M5.625 9.063']), button:has(path[d^='M4.627 4.774'])"
        }
      }
    ]
    """

    static let signInURL = URL(string: "https://accounts.firefox.com/?service=sync&context=fx_desktop_v3&entrypoint=zencompanion&action=email")!

    /// Listen for FxA WebChannel events and reply like Firefox desktop.
    /// Login keys are cached in sessionStorage so a redirect right after
    /// sign-in does not drop them before Swift can finish.
    static let bridgeJS = """
    (function() {
      if (window.__zencompanionBridge) return;
      window.__zencompanionBridge = true;
      try {
        Object.defineProperty(navigator, 'userAgent', {
          get: function() { return '\(firefoxDesktopUA)'; }
        });
      } catch (e) {}

      function post(obj) {
        try { window.webkit.messageHandlers.fxa.postMessage(obj); } catch (e) {}
      }

      function reply(message) {
        var payload = { id: 'account_updates', message: message };
        try {
          window.dispatchEvent(new CustomEvent('WebChannelMessageToContent', { detail: payload }));
        } catch (e) {}
        try {
          window.dispatchEvent(new CustomEvent('WebChannelMessageToContent', {
            detail: JSON.stringify(payload)
          }));
        } catch (e) {}
      }

      function flattenLogin(data) {
        if (!data) return null;
        var email = data.email || '';
        var uid = data.uid || '';
        var sessionToken = data.sessionToken || data.session_token || '';
        var keyFetchToken = data.keyFetchToken || data.key_fetch_token || '';
        var unwrapBKey = data.unwrapBKey || data.unwrap_b_key || '';
        if (!email || !uid || !sessionToken || !keyFetchToken || !unwrapBKey) return null;
        return {
          command: 'zencompanion:login',
          email: email,
          uid: uid,
          sessionToken: sessionToken,
          keyFetchToken: keyFetchToken,
          unwrapBKey: unwrapBKey
        };
      }

      function cacheLogin(flat) {
        try { sessionStorage.setItem('zencompanion.login', JSON.stringify(flat)); } catch (e) {}
      }

      function capture(data) {
        var flat = flattenLogin(data);
        if (!flat) return false;
        cacheLogin(flat);
        post(flat);
        return true;
      }

      function replay() {
        try {
          var raw = sessionStorage.getItem('zencompanion.login');
          if (raw) post(JSON.parse(raw));
        } catch (e) {}
      }

      function handle(event) {
        var detail = event.detail;
        if (typeof detail === 'string') {
          try { detail = JSON.parse(detail); } catch (e) { return; }
        }
        if (!detail || detail.id !== 'account_updates' || !detail.message) return;
        var msg = detail.message;
        var command = msg.command || '';
        if (command === 'fxaccounts:fxa_status') {
          reply({
            command: command,
            messageId: msg.messageId,
            data: {
              signedInUser: null,
              clientId: (msg.data && msg.data.service) ? msg.data.service : 'sync',
              capabilities: {
                engines: ['addresses', 'creditcards'],
                choose_what_to_sync: false,
                pairing: false,
                multiService: false
              }
            }
          });
          replay();
          return;
        }
        if (command === 'fxaccounts:can_link_account') {
          reply({ command: command, messageId: msg.messageId, data: { ok: true } });
          return;
        }
        if (command === 'fxaccounts:login' || command === 'fxaccounts:verified' || command === 'fxaccounts:change_password' || command === 'fxaccount:change_password') {
          // Surface the wait-for-email state, but always capture the login:
          // after confirming (email link or code) the page may redirect
          // without re-emitting a verified login, so dropping the tokens
          // here would leave the sheet stuck with no checkmark on a first
          // login. The cached copy lets replay() recover after a redirect,
          // and /account/keys polling in finish() waits for the
          // confirmation (the "finishing" capsule is non-blocking, so the
          // confirmation code can still be entered while it polls).
          if (msg.data && msg.data.verified === false) {
            post({ command: 'zencompanion:unverified' });
          }
          capture(msg.data);
        }
      }

      window.addEventListener('WebChannelMessageToChrome', handle);
      document.addEventListener('WebChannelMessageToChrome', handle);
      replay();

      function zenHideSocial() {
        try {
          var buttons = document.querySelectorAll('button');
          for (var i = 0; i < buttons.length; i++) {
            var el = buttons[i];
            var label = (el.getAttribute('aria-label') || '').toLowerCase();
            var text = (el.innerText || el.textContent || '').toLowerCase();
            var html = (el.innerHTML || '').toLowerCase();
            var blob = label + ' ' + text + ' ' + html;
            var social = /continue with apple|continue with google|sign in with apple|sign in with google/.test(blob);
            if (!social && /apple|google/.test(blob) && text.replace(/\\s+/g, '') === '') social = true;
            var w = el.offsetWidth, h = el.offsetHeight;
            if (!social && w >= 52 && w <= 72 && h >= 52 && h <= 72) social = true;
            if (!social) continue;
            var parent = el.parentElement;
            try { el.remove(); } catch (err) {}
            if (parent && parent.children && parent.children.length === 0) {
              try { parent.remove(); } catch (err2) {}
            }
          }
        } catch (err) {}
      }
      window.__zenHideSocial = zenHideSocial;
      zenHideSocial();
      var ticks = 0;
      var iv = setInterval(function() {
        zenHideSocial();
        if (++ticks > 80) clearInterval(iv);
      }, 250);
      try {
        new MutationObserver(zenHideSocial).observe(document.documentElement, {
          childList: true,
          subtree: true,
          attributes: true
        });
      } catch (err) {}

      // Passkey sign-in cannot work in this embedded WebView: platform
      // passkeys need an app<->accounts.firefox.com association, and
      // Mozilla's AASA/assetlinks do not list this app. Hide the CTA so
      // nobody lands in a dead end; the help sheet explains why.
      function zenHidePasskeys() {
        try {
          var nodes = document.querySelectorAll('button, a');
          for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            var text = (el.innerText || el.textContent || '').toLowerCase();
            var html = el.innerHTML || '';
            // Text covers most locales; the passkey icon path covers the rest.
            var passkey = /passkey/.test(text) ||
              html.indexOf('M5.625 9.063') >= 0 ||
              html.indexOf('M4.627 4.774') >= 0;
            if (!passkey) continue;
            var container = el.parentElement;
            var root = container ? container.parentElement : null;
            try { el.remove(); } catch (err) {}
            var removedContainer = false;
            if (container && container.children.length === 0) {
              try { container.remove(); removedContainer = true; } catch (err2) {}
            }
            if (removedContainer && root && root.querySelectorAll('button, a, input').length === 0) {
              try { root.remove(); } catch (err3) {}
            }
          }
          // Drop an orphaned "or" divider once every alternative button is gone.
          var lines = document.querySelectorAll('div.flex-1.h-px');
          for (var j = 0; j < lines.length; j++) {
            var divider = lines[j].parentElement;
            var box = divider ? divider.parentElement : null;
            if (!box || box.querySelector('button, a')) continue;
            try { divider.remove(); } catch (err4) {}
            if (box.children.length === 0) {
              try { box.remove(); } catch (err5) {}
            }
          }
        } catch (err) {}
      }
      window.__zenHidePasskeys = zenHidePasskeys;
      zenHidePasskeys();
      var passkeyTicks = 0;
      var passkeyIv = setInterval(function() {
        zenHidePasskeys();
        if (++passkeyTicks > 80) clearInterval(passkeyIv);
      }, 250);
      try {
        new MutationObserver(zenHidePasskeys).observe(document.documentElement, {
          childList: true,
          subtree: true,
          attributes: true
        });
      } catch (err) {}
    })();
    """

    final class Coordinator: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        var onLogin: (FxAWebLogin) -> Void
        var onUnverified: () -> Void
        var onError: (String) -> Void
        weak var webView: WKWebView?
        private var delivered = false
        private var didLoad = false

        init(
            onLogin: @escaping (FxAWebLogin) -> Void,
            onUnverified: @escaping () -> Void,
            onError: @escaping (String) -> Void
        ) {
            self.onLogin = onLogin
            self.onUnverified = onUnverified
            self.onError = onError
        }

        func attachAndLoad(_ web: WKWebView) {
            webView = web
            guard !didLoad else { return }
            let json = FxAWebView.socialHideRulesJSON
            guard let store = WKContentRuleListStore.default() else {
                loadSignIn(web)
                return
            }
            store.compileContentRuleList(
                forIdentifier: "zen-hide-fxa-social-v4",
                encodedContentRuleList: json
            ) { [weak self] list, error in
                if let list {
                    DispatchQueue.main.async {
                        web.configuration.userContentController.add(list)
                        self?.loadSignIn(web)
                    }
                    return
                }
                // :has() may fail on older rule compilers; buttons still match.
                let fallback = """
                [{"trigger":{"url-filter":".*","if-domain":["*accounts.firefox.com"]},"action":{"type":"css-display-none","selector":"button[aria-label*='Apple'], button[aria-label*='Google'], button[aria-label*='apple'], button[aria-label*='google']"}}]
                """
                store.compileContentRuleList(
                    forIdentifier: "zen-hide-fxa-social-v4-fallback",
                    encodedContentRuleList: fallback
                ) { list2, _ in
                    DispatchQueue.main.async {
                        if let list2 {
                            web.configuration.userContentController.add(list2)
                        }
                        self?.loadSignIn(web)
                    }
                }
            }
        }

        private func loadSignIn(_ web: WKWebView) {
            guard !didLoad else { return }
            didLoad = true
            web.load(URLRequest(url: FxAWebView.signInURL))
        }

        /// Only the real FxA auth host may drive this WebView. Kept as a
        /// static helper so navigation and JS gating classify identically.
        static func isAllowedAuthHost(_ url: URL) -> Bool {
            url.host?.lowercased() == "accounts.firefox.com"
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            // Every frame (hostile iframes included) can post to this
            // handler; only messages originating from the auth host count.
            guard message.frameInfo.securityOrigin.host.lowercased() == "accounts.firefox.com" else { return }
            let body: [String: Any]
            if let dict = message.body as? [String: Any] {
                body = dict
            } else if let text = message.body as? String,
                      let data = text.data(using: .utf8),
                      let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                body = dict
            } else {
                return
            }
            let command = body["command"] as? String ?? ""
            if command == "zencompanion:unverified" {
                onUnverified()
                return
            }
            let data = (body["data"] as? [String: Any]) ?? body
            guard command == "zencompanion:login"
                    || command == "fxaccounts:login"
                    || command == "fxaccounts:verified"
                    || command == "fxaccounts:change_password"
                    || command == "fxaccount:change_password"
            else { return }
            guard let email = string(data, "email"),
                  let uid = string(data, "uid"),
                  let session = string(data, "sessionToken") ?? string(data, "session_token"),
                  let keyFetch = string(data, "keyFetchToken") ?? string(data, "key_fetch_token"),
                  let unwrap = string(data, "unwrapBKey") ?? string(data, "unwrap_b_key")
            else { return }
            // Ignore explicitly-unverified logins: the user still has to
            // enter the confirmation code, so finishing now would freeze the
            // page behind a full-screen spinner.
            if let verified = data["verified"] as? Bool, !verified { return }
            guard !delivered else { return }
            delivered = true
            onLogin(FxAWebLogin(
                email: email,
                uid: uid,
                sessionToken: session,
                keyFetchToken: keyFetch,
                unwrapBKey: unwrap
            ))
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            // All page manipulation stays on the auth host.
            guard let url = webView.url, Self.isAllowedAuthHost(url) else { return }
            webView.evaluateJavaScript(
                "try { window.__zenHideSocial && window.__zenHideSocial(); } catch (e) {}"
            )
            webView.evaluateJavaScript(
                "try { window.__zenHidePasskeys && window.__zenHidePasskeys(); } catch (e) {}"
            )
            webView.evaluateJavaScript(
                "try { var r = sessionStorage.getItem('zencompanion.login'); if (r) window.webkit.messageHandlers.fxa.postMessage(JSON.parse(r)); } catch (e) {}"
            )
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            onError(error.localizedDescription)
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            let ns = error as NSError
            if ns.domain == NSURLErrorDomain, ns.code == NSURLErrorCancelled { return }
            onError(error.localizedDescription)
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
        ) {
            if let url = navigationAction.request.url, FxASocialAuth.shouldBlock(url) {
                decisionHandler(.cancel)
                return
            }
            // Main-frame loads must stay on the auth host; subresources and
            // iframes are untouched. Links to other hosts open externally,
            // every other off-host main-frame navigation is dropped.
            let isMain = navigationAction.targetFrame?.isMainFrame ?? true
            if isMain, let url = navigationAction.request.url, !Self.isAllowedAuthHost(url) {
                if navigationAction.navigationType == .linkActivated {
                    ExternalBrowser.open(url)
                }
                decisionHandler(.cancel)
                return
            }
            decisionHandler(.allow)
        }

        private func string(_ data: [String: Any], _ key: String) -> String? {
            guard let value = data[key] as? String, !value.isEmpty else { return nil }
            return value
        }
    }
}
