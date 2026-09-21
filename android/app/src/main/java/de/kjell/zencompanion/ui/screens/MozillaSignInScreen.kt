package de.kjell.zencompanion.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import de.kjell.zencompanion.R
import de.kjell.zencompanion.ui.components.openURLExternally
import de.kjell.zencompanion.ui.theme.LocalZenColors
import de.kjell.zencompanion.ui.theme.ZenType
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

class FxAWebLogin(
    val email: String,
    val uid: String,
    val sessionToken: String,
    val keyFetchToken: String,
    val unwrapBKey: String,
)

/**
 * Port of `MozillaSignInView` + `FxAWebView`: official Mozilla accounts page +
 * WebChannel (same path as Firefox desktop). Direct POST /account/login is
 * blocked by Mozilla's CDN (HTTP 406), so the bridge replies like Firefox.
 */
object FxAWeb {
    const val loginURL =
        "https://accounts.firefox.com/?service=sync&context=fx_desktop_v3&entrypoint=zencompanion&action=email"

    const val firefoxDesktopUA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:140.0) Gecko/20100101 Firefox/140.0"

    /**
     * The entire `bridgeJS` from MozillaSignInView.swift, verbatim except the
     * `post()` transport which targets the Android JavascriptInterface
     * (`AndroidBridge.fxa`) instead of `window.webkit.messageHandlers.fxa`,
     * plus a per-WebView `znonce` handshake: the Android interface is exposed
     * to every frame, so native only accepts messages stamped with the nonce
     * this script was injected with.
     */
    fun bridgeJS(ua: String, nonce: String): String = """
    (function() {
      if (window.__zencompanionBridge) return;
      window.__zencompanionBridge = true;
      window.__znonce = '$nonce';
      try {
        Object.defineProperty(navigator, 'userAgent', {
          get: function() { return '$ua'; }
        });
      } catch (e) {}

      function post(obj) {
        obj.znonce = window.__znonce;
        try { AndroidBridge.fxa(JSON.stringify(obj)); } catch (e) {}
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

      function capture(data) {
        var flat = flattenLogin(data);
        if (!flat) return false;
        try { sessionStorage.setItem('zencompanion.login', JSON.stringify(flat)); } catch (e) {}
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
          // after clicking the email link the page may redirect without
          // re-emitting a verified login, so dropping the tokens here would
          // leave the sheet stuck. /account/keys polling handles the rest.
          if (msg.data && msg.data.verified === false) {
            post({ command: 'zencompanion:unverified' });
          }
          capture(msg.data);
        }
      }

      window.addEventListener('WebChannelMessageToChrome', handle);
      document.addEventListener('WebChannelMessageToChrome', handle);
      replay();

      // Passkey sign-in cannot work in this embedded WebView: platform
      // passkeys need an app<->accounts.firefox.com association, and
      // Mozilla's AASA/assetlinks do not list this app. Hide the CTA so
      // nobody lands in a dead end.
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
    """.trimIndent()

    /**
     * Port of the `didFinish` replay snippet (message handler renamed),
     * nonce-tagged. Only `window.__znonce` is used — no literal fallback, so
     * a page where the bridge was never injected cannot stamp valid messages.
     */
    fun replayJS(): String =
        "try { var r = sessionStorage.getItem('zencompanion.login'); if (r) { var o = JSON.parse(r); o.znonce = window.__znonce; AndroidBridge.fxa(JSON.stringify(o)); } } catch (e) {} " +
            "try { window.__zenHidePasskeys && window.__zenHidePasskeys(); } catch (e) {}"

    /**
     * Port of `wipeWebSession`: cookies and local storage outlive sign-out —
     * without this purge the next sign-in would silently reuse the previous
     * web session.
     */
    fun wipeWebSession() {
        // The Android cookie jar is process-wide, so the full cookie wipe is
        // the only reliable FxA session purge.
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            removeSessionCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteOrigin("https://accounts.firefox.com")
        lastWebView?.let { web ->
            Handler(Looper.getMainLooper()).post {
                web.clearCache(true)
                web.clearFormData()
                web.clearHistory()
                web.clearSslPreferences()
                lastWebView = null
            }
        }
    }

    @Volatile
    internal var lastWebView: WebView? = null
}

/** Parses one bridge message exactly like the Swift Coordinator did. */
internal fun parseBridgeMessage(json: String): FxAWebLogin? {
    val body = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val command = body.optString("command")
    if (command == "zencompanion:unverified") return null
    if (command != "zencompanion:login" &&
        command != "fxaccounts:login" &&
        command != "fxaccounts:verified" &&
        command != "fxaccounts:change_password" &&
        command != "fxaccount:change_password"
    ) {
        return null
    }
    val data = body.optJSONObject("data") ?: body
    fun str(key: String, altKey: String? = null): String? {
        if (!data.has(key) || data.isNull(key)) {
            altKey?.let { a ->
                if (data.has(a) && !data.isNull(a)) {
                    val w = data.optString(a)
                    if (w.isNotEmpty()) return w
                }
            }
            return null
        }
        val v = data.optString(key)
        if (v.isNotEmpty()) return v
        altKey?.let { a ->
            val w = data.optString(a)
            if (w.isNotEmpty()) return w
        }
        return null
    }
    val email = str("email") ?: return null
    val uid = str("uid") ?: return null
    val session = str("sessionToken", "session_token") ?: return null
    val keyFetch = str("keyFetchToken", "key_fetch_token") ?: return null
    val unwrap = str("unwrapBKey", "unwrap_b_key") ?: return null
    return FxAWebLogin(email, uid, session, keyFetch, unwrap)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MozillaSignInScreen(
    onFinishLogin: suspend (FxAWebLogin) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalZenColors.current
    var pending by remember { mutableStateOf<FxAWebLogin?>(null) }
    var finishing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var unverifiedHint by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cancelText = stringResource(R.string.common_cancel)
    val confirmText = stringResource(R.string.signin_confirm)
    val unverifiedText = stringResource(R.string.signin_unverified_hint)

    suspend fun finish(login: FxAWebLogin) {
        if (finishing) return
        finishing = true
        error = null
        unverifiedHint = false
        runCatching { onFinishLogin(login) }
            .onFailure { e ->
                finishing = false
                error = e.message ?: e.toString()
            }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color(0xFFFBFBFC)),
    ) {
        // Material 3 Full-Screen Header Bar: Close left; Confirm checkmark right when pending login exists
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(
                onClick = { if (!finishing) onCancel() },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = cancelText,
                    tint = androidx.compose.ui.graphics.Color(0xFF2B2A33),
                )
            }

            if (pending != null && !finishing) {
                IconButton(
                    onClick = { pending?.let { scope.launch { finish(it) } } },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = confirmText,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { context ->
                    // Per-WebView handshake secret: the JavascriptInterface is
                    // reachable from every frame, so bridge messages must carry
                    // this nonce to be accepted by the native side.
                    val bridgeNonce = UUID.randomUUID().toString()
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(Color.TRANSPARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = FxAWeb.firefoxDesktopUA
                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun fxa(json: String) {
                                    Handler(Looper.getMainLooper()).post {
                                        val body = runCatching { JSONObject(json) }.getOrNull() ?: return@post
                                        // Silent drop: anything that did not come
                                        // from the injected bridge JS is hostile.
                                        if (body.optString("znonce") != bridgeNonce) return@post
                                        if (body.optString("command") == "zencompanion:unverified") {
                                            unverifiedHint = true
                                            return@post
                                        }
                                        parseBridgeMessage(json)?.let { login ->
                                            unverifiedHint = false
                                            pending = login
                                            // Port of iOS behavior: the sheet closes
                                            // itself as soon as the WebChannel delivers
                                            // the login — no manual checkmark tap. The
                                            // checkmark stays as a fallback if the
                                            // auto-finish fails.
                                            scope.launch { finish(login) }
                                        }
                                    }
                                }
                            },
                            "AndroidBridge",
                        )
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                if (!request.isForMainFrame) return false
                                if (request.url.host == "accounts.firefox.com") return false
                                // Off-host navigations must never load inside the
                                // auth WebView; user-initiated ones go external.
                                if (request.hasGesture()) openURLExternally(view.context, request.url)
                                return true
                            }

                            override fun onPageStarted(
                                view: WebView,
                                url: String?,
                                favicon: android.graphics.Bitmap?,
                            ) {
                                // Document-start bridge (port of the WKUserScript
                                // atDocumentStart injection): answers the page's
                                // `fxaccounts:fxa_status` WebChannel request. The
                                // `__zencompanionBridge` guard makes re-injection a
                                // no-op, so this is safe on every navigation.
                                if (url != null && runCatching { Uri.parse(url).host }.getOrNull() == "accounts.firefox.com") {
                                    view.evaluateJavascript(
                                        FxAWeb.bridgeJS(FxAWeb.firefoxDesktopUA, bridgeNonce),
                                        null,
                                    )
                                }
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                // Replay and page-constant hiding only run on the
                                // auth host; other pages get no bridge interaction.
                                if (url != null && runCatching { Uri.parse(url).host }.getOrNull() == "accounts.firefox.com") {
                                    view.evaluateJavascript(FxAWeb.replayJS(), null)
                                }
                            }

                            override fun onReceivedError(
                                view: WebView,
                                errorCode: Int,
                                description: String?,
                                failingUrl: String?,
                            ) {
                                if (failingUrl == view.url) error = description
                            }
                        }
                        webChromeClient = WebChromeClient()
                        loadUrl(FxAWeb.loginURL)
                        FxAWeb.lastWebView = this
                    }
                },
                update = {},
                modifier = Modifier.fillMaxSize(),
            )

            if (finishing) {
                // Non-blocking top pill (port of the iOS "finishing" capsule):
                // the page must stay interactive so the confirmation code can
                // still be entered while /account/keys polling waits for it.
                // A full-screen scrim here would freeze a first login.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier
                            .background(
                                androidx.compose.ui.graphics.Color.White,
                                RoundedCornerShape(percent = 50),
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            color = colors.coral,
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.signin_finishing),
                            style = ZenType.rounded(14, FontWeight.Medium),
                            color = colors.ink.copy(alpha = 0.75f),
                        )
                    }
                }
            }

            if (unverifiedHint || error != null) {
                Column(
                    // Pinned under the header bar so the keyboard never
                    // pushes it over the page's input fields.
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (unverifiedHint) {
                        NoticeBubble(
                            text = unverifiedText,
                            bubbleColor = HintBubbleBlue12,
                            borderColor = androidx.compose.ui.graphics.Color(0xFF0060DF).copy(alpha = 0.35f),
                            onClose = { unverifiedHint = false },
                        )
                    }
                    error?.let { text ->
                        NoticeBubble(
                            text = text,
                            bubbleColor = ErrorBubbleRed12,
                            borderColor = androidx.compose.ui.graphics.Color(0xFFE03D33).copy(alpha = 0.35f),
                            onClose = { error = null },
                        )
                    }
                }
            }
        }
    }
}

private val ErrorBubbleRed12 = androidx.compose.ui.graphics.Color(0xFFE03D33).copy(alpha = 0.12f)
private val HintBubbleBlue12 = androidx.compose.ui.graphics.Color(0xFF0060DF).copy(alpha = 0.12f)

/** Opaque notice bubble with a close button, pinned under the header. */
@Composable
private fun NoticeBubble(
    text: String,
    bubbleColor: androidx.compose.ui.graphics.Color,
    borderColor: androidx.compose.ui.graphics.Color,
    onClose: () -> Unit,
) {
    val closeText = stringResource(R.string.common_close)
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(12.dp),
        color = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .background(bubbleColor, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = text,
                style = ZenType.rounded(13, FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = closeText,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Icon-only toolbar button: native IconButton (48dp target + ripple). */
@Composable
internal fun ToolbarIcon(
    resId: Int,
    label: String,
    modifier: Modifier = Modifier,
    tintOverride: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit,
) {
    val colors = LocalZenColors.current
    androidx.compose.material3.IconButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Image(
            painter = painterResource(resId),
            contentDescription = label,
            colorFilter = ColorFilter.tint(tintOverride ?: colors.ink),
            modifier = Modifier.size(22.dp),
        )
    }
}
