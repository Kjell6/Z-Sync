# Z-Sync — Privacy Policy

**Controller:** Kjell Behrends, Germany  
**Contact:** [Support@Kjell.cc](mailto:Support@Kjell.cc)

Z-Sync ("the app") is an independent mobile app. It lets you view the spaces and tabs of your own Mozilla account Sync and send links into them. This policy explains which data the app processes, where it goes, and what rights you have.

---

## 1. Summary

- Z-Sync has **no servers of its own**. The developer operates no backend, receives no analytics, and collects nothing for himself.
- Your Mozilla sign-in happens **directly on Mozilla's website** inside the app. Credentials go to Mozilla only.
- Sync content is **encrypted on your device** before it ever leaves it; Mozilla's servers store only ciphertext.
- To display favicon previews, the app asks **the website of the synced tab** for its own icon, sending the address without its query string. Only when a site offers no reachable icon does the bare domain go to Google's public favicon service (see section 5.2).
- While you type in the mini-browser address field, that text is sent to the **suggestion service of the search engine you chose** (DuckDuckGo, Google, Ecosia, Brave, or Bing). An engine you added yourself has no suggestion service, so nothing is sent until you submit. Z-Sync does not run a suggestion server.
- All remaining data stays **on your device**: Keychain / Android Keystore and local app storage.
- Signing out or deleting the app removes that local data.

---

## 2. Data the developer does not collect

The app contains:

- **No analytics or tracking SDKs**
- **No advertising frameworks**
- **No crash-reporting services**
- **No social plug-ins**
- **No developer-operated endpoints** of any kind

Nothing about you is transmitted to, stored by, or visible to the developer. Data that leaves the device goes only to Mozilla, to the websites whose tabs you sync, and — solely as a favicon fallback — to Google as described below, so the app can function. While you type a search in the mini-browser, that text itself goes to the suggestion endpoint of the search engine you selected.

---

## 3. Data stored locally on your device

While you use the app, the following information is kept **only on your device**:

- **Mozilla account email and user ID** — iOS Keychain / Android EncryptedSharedPreferences (Keystore) — showing which account is connected
- **Mozilla session token and account key (`kB`)** — same secure storage — re-authenticating with Sync so you don't log in every time
- **Cached preview data** (space names, icons, themes; pinned-tab titles, URLs and icons) — local app storage (`spaces-cache.json`) — instant display while fresh data loads
- **Cached website icons** (the image and the address it was loaded from) — the app's local cache — showing a synced tab again without downloading its page
- **Preferences** (last selected space, cache timestamp) — local preferences — convenience
- **Content blocking settings and lists** (the three blocking switches, the sites where you turned one off, and the downloaded ad and tracker lists) — local preferences and app storage — blocking in the mini-browser

Notes:

- On iOS the Keychain entry uses Apple's *device-only* protection class: it is unavailable until first unlock and is **not included in device backups**. The cached preview file lives in the shared app-group container so the share extension can respond instantly; it may be included in encrypted device backups like any other app file.
- On Android the session secrets live in EncryptedSharedPreferences backed by the Android Keystore and are excluded from backups.

## 4. Signing in via Mozilla

Sign-in does not run through the developer. The app opens Mozilla's official accounts page (`accounts.firefox.com`) in an embedded browser view, and you enter your credentials directly there. The developer never sees your password.

Mozilla's page sets cookies and local browser storage inside the app so you don't have to re-enter everything during a session. When you sign out, the app **wipes all of that website data**, together with the stored credentials and the cached preview file (see section 7).

---

## 5. Data transmitted to third parties

### 5.1 Mozilla (authentication & sync)

To do anything useful, the app must talk to the same infrastructure your Firefox-family browsers use:

- `accounts.firefox.com` / `api.accounts.firefox.com` / `oauth.accounts.firefox.com` — sign-in, tokens, certificates; includes your email at login time
- `token.services.mozilla.com` — obtaining access to your personal Sync storage node; session-derived tokens
- Mozilla Sync storage nodes — reading/writing your spaces and tabs; **encrypted payloads only**

Your sync content (tab URLs, titles, space structure) is **encrypted on your device** (AES-256 with HMAC-SHA256 authentication) using keys derived from your account key, which Mozilla's servers never receive. Mozilla stores and relays ciphertext it cannot read.

**Legal basis:** performance of a contract / necessity for the service you requested — Art. 6(1)(b) GDPR. You cannot use Z-Sync without this connection; if you prefer not to make it, delete the app.

Mozilla's own handling of your account data is governed by the [Mozilla Privacy Notice](https://www.mozilla.org/privacy/).

### 5.2 Website icons (favicon previews)

Tabs that have no built-in icon get their favicon from the website itself. The app does not use a search-engine favicon API as its normal path.

The synced tab address is enough. The tab is not opened in a browser inside the app, and no cookies are sent. The app requests that address with the query string, the fragment, and any username or password removed, reads the icon links in the start of the document, and also tries `/favicon.ico`. An icon declared as SVG is downloaded and drawn on the device. A few products only publish the real icon inside a signed-in page (Google Calendar, Google Docs, Sheets, Slides and Forms, and Shopify admin). For those, the app requests the public icon file that product publishes, and skips the logged-out page.

- **Transmitted:** that request, to the website or to the host that serves its icon, and your device IP address as part of any web request. A private or self-hosted dashboard is contacted from your device. An address on `localhost` is not requested, because that is the desktop, not the phone.
- **Not transmitted:** the query string, account data, tab titles, or your identity.
- Some tabs already carry a direct icon URL from sync. Those images load from that address.
- The chosen icon address stays in the on-device cache, so the next launch does not download the page again.

**Last resort:** a few sites publish no icon the app can reach — they answer a signed-in redirect, a `403`, or no icon link at all (for example Google AI Studio). Only for those, and only after every request above has failed, the app asks Google's public favicon service (`google.com/s2/favicons`) for the domain's icon.

- **Transmitted to Google:** the bare domain and your IP address. Google's handling is governed by the [Google Privacy Policy](https://policies.google.com/privacy).
- **Not transmitted:** the tab address, its query string, account data, or your identity.
- This lookup never runs when the website itself already supplied an icon, and **never for a private or local address** (`localhost`, a LAN IP such as `192.168.x.x`, a `.local`/`.internal` name, or a single-name intranet host). Those stay on your network.
- Icons — including ones drawn from SVG — are cached, so a later launch re-downloads neither the page nor the icon.

**Legal basis:** legitimate interest — Art. 6(1)(f) GDPR. The interest is a usable interface in which entries are recognisable at a glance. Requests go first to a site you already have open in your browser, without an account identifier; the domain-only fallback runs only when that fails. If you object to these lookups, contact us; removing the app stops all such requests.

### 5.3 Search suggestions

The mini-browser address field shows suggestions while you type, for a built-in search engine only. The text is sent **directly from the app** to that engine's public suggestion endpoint. There is no Z-Sync server in between.

- DuckDuckGo — `https://duckduckgo.com/ac/`
- Google — `https://suggestqueries.google.com/complete/search`
- Ecosia — `https://ac.ecosia.org/autocomplete`
- Brave — `https://search.brave.com/api/suggest`
- Bing — `https://www.bing.com/osjson.aspx`

- **Transmitted:** the text currently in the address field, and your IP address as part of the HTTPS request.
- **Not transmitted:** your Mozilla account, sync data, or anything to the developer. The request does not include the cookies of the page you are viewing.
- **Not sent at all** when the field is empty, when the text is already a web address, or when the selected engine is one you added yourself. Those engines only have a search link, so a suggestion lookup does not exist for them.
- Submitting the field yourself still opens that engine's search page, as before. That page load goes to the engine, not to the developer.

**Legal basis:** performance of the feature you are using — Art. 6(1)(b) GDPR. Pick a custom search engine, or do not type a query, if you do not want these lookups. Removing the app stops them.

### 5.4 Ad and tracker lists (content blocking)

The mini-browser can block ads, trackers and cookie banners. The ad and tracker lists ship with the app, and once a week the app downloads fresh copies from the EasyList project:

- `https://easylist.to/easylist/easylist.txt`
- `https://easylist.to/easylist/easyprivacy.txt`

- **Transmitted:** a plain request for the two list files, and your IP address as part of the HTTPS request.
- **Not transmitted:** the pages you visit, your Mozilla account, sync data, or anything to the developer. Which requests get blocked is decided on your device.
- Cookie banners are answered on your device by DuckDuckGo's open-source autoconsent rules, which ship with the app. Nothing is sent to DuckDuckGo for this.
- The download is skipped while both ad and tracker blocking are off in Settings. Removing the app stops it.

**Legal basis:** legitimate interest — Art. 6(1)(f) GDPR. The interest is a mini-browser that keeps its blocking lists current without anyone tracking which pages you open.

---

## 6. International data transfers

Mozilla Corporation is based in the United States. Mozilla states that it participates in the EU–U.S. Data Privacy Framework (EU adequacy decision) and offers Standard Contractual Clauses as described in the [Mozilla Privacy Notice](https://www.mozilla.org/privacy/). Mozilla acts as an independent controller of the account and sync data described above.

Favicon requests go from your device to the website of the synced tab, or to the host that website uses for its icon. A few Google and Shopify product icons are loaded from those companies' public icon files. When no icon can be reached from the site itself, a domain-only request goes to Google's public favicon service, as described in section 5.2. Where those hosts are, and how they handle the request, is each site's own matter. The requests do not include your account or the tab's query string.

Search suggestions go to whichever built-in engine you selected. DuckDuckGo is based in the United States and states that it participates in the EU–U.S. Data Privacy Framework (EU adequacy decision). Ecosia GmbH is based in Germany. Google LLC, Microsoft Corporation (Bing), and Brave Software, Inc. are based in the United States. Each provider is an independent controller of the text it receives. Their notices:

- [DuckDuckGo Privacy Policy](https://duckduckgo.com/privacy)
- [Google Privacy Policy](https://policies.google.com/privacy)
- [Microsoft Privacy Statement](https://privacy.microsoft.com/privacystatement)
- [Brave Privacy Policy](https://brave.com/privacy/)
- [Ecosia Privacy Policy](https://www.ecosia.org/privacy)

Please refer to those policies for how they handle a suggestion query.

The weekly list download goes to `easylist.to`, run by the EasyList project, which receives only the request for the list files and your IP address.

---

## 7. Retention and deletion

- **Normal use:** local data listed in section 3 persists on your device; server-side, Mozilla retains your sync data per its own retention rules.
- **Signing out:** the app deletes the stored credentials, all embedded-browser cookies/storage, and the cached preview file.
- **Deleting the app:** everything above is removed automatically, including Keychain / Keystore items.
- **Server-side data:** your actual Mozilla sync data lives in your Mozilla account, outside this app's control; manage or delete it through Firefox or your Mozilla account settings.

---

## 8. Diagnostic logs

The app writes technical logs (connection errors, record counts) to the platform’s on-device logging system. These logs stay on your device, are not uploaded anywhere by the app, and can only leave the device if you voluntarily share them (e.g. via Console or a bug report you choose to file).

---

## 9. Your rights (GDPR)

If you are in the EU/EEA, you have the right to:

- Access (Art. 15), rectification (Art. 16), erasure (Art. 17), restriction of processing (Art. 18), data portability (Art. 20), and objection (Art. 21)
- Lodge a complaint with a supervisory authority — in Germany, the Unabhängiges Landeszentrum für Datenschutz Schleswig-Holstein ([ULD](https://www.datenschutzzentrum.de)) or the Federal Commissioner ([BfDI](https://www.bfdi.bund.de))

Because the developer holds **no data about you** (nothing ever reaches him), most requests concern data held by **Apple, Google, or Mozilla**, by **the search engine you selected**, or by a website whose icon the app loaded — please direct such requests to those companies. Anything genuinely local to the app you control yourself: sign out or delete the app.

Questions or objections: Support@Kjell.cc

---

## 10. Children

Z-Sync is a general-audience utility and does not knowingly process data of children under 16. It has no user-generated content, chat, or sharing features.

---

## 11. Security measures

- Connections to Mozilla use TLS (HTTPS).
- A favicon request uses the scheme of the tab address. On iOS, unencrypted HTTP is allowed only on the local network, so a dashboard on your LAN can be reached; public sites still require HTTPS. On Android, the system blocks unencrypted HTTP, so a local dashboard needs HTTPS for its icon to load.
- Sync payloads are end-to-end encrypted client-side before upload (section 5.1).
- Long-lived secrets are held in the iOS Keychain (*ThisDeviceOnly*) or Android Keystore-backed encrypted preferences.
- The iOS app ships a privacy manifest declaring no tracking (Apple `PrivacyInfo.xcprivacy`).
- Minimal attack surface: zero third-party analytics/ad SDKs compiled into the app.

---

## 12. Changes to this policy

If the app's behaviour changes (e.g. new third-party services), this policy will be updated and the version date above will change. Material changes will be reflected in the store release notes.

