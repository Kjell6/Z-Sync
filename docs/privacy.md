# Z-Sync — Privacy Policy

**Controller:** Kjell Behrends, Germany  
**Contact:** [Support@Kjell.cc](mailto:Support@Kjell.cc)

Z-Sync ("the app") is an independent mobile app. It lets you view the spaces and tabs of your own Mozilla account Sync and send links into them. This policy explains which data the app processes, where it goes, and what rights you have.

---

## 1. Summary

- Z-Sync has **no servers of its own**. The developer operates no backend, receives no analytics, and collects nothing for himself.
- Your Mozilla sign-in happens **directly on Mozilla's website** inside the app. Credentials go to Mozilla only.
- Sync content is **encrypted on your device** before it ever leaves it; Mozilla's servers store only ciphertext.
- To display favicon previews, website domains are sent to **DuckDuckGo's icon service**. No account or personal data is attached.
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

Nothing about you is transmitted to, stored by, or visible to the developer. Data that leaves the device goes only to Mozilla and DuckDuckGo as described below, so the app can function.

---

## 3. Data stored locally on your device

While you use the app, the following information is kept **only on your device**:

- **Mozilla account email and user ID** — iOS Keychain / Android EncryptedSharedPreferences (Keystore) — showing which account is connected
- **Mozilla session token and account key (`kB`)** — same secure storage — re-authenticating with Sync so you don't log in every time
- **Cached preview data** (space names, icons, themes; pinned-tab titles, URLs and icons) — local app storage (`spaces-cache.json`) — instant display while fresh data loads
- **Preferences** (last selected space, cache timestamp) — local preferences — convenience

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

### 5.2 DuckDuckGo icon service (favicon previews)

Tabs that have no built-in icon get their favicon fetched from `icons.duckduckgo.com`, operated by Duck Duck Go, LLC (USA).

- **Transmitted:** the website's domain (e.g. `example.org`) and your device IP address as part of any HTTPS request.
- **Not transmitted:** account data, tab titles, your identity, anything else.
- DuckDuckGo states it does not retain such requests or link them to individuals — see the [DuckDuckGo Privacy Policy](https://duckduckgo.com/privacy).
- Some tabs carry a direct icon URL from your sync data; those images load from the respective website itself instead.

**Legal basis:** legitimate interest — Art. 6(1)(f) GDPR. Our interest is a usable interface in which entries are recognisable at a glance; this interest is not overridden by your rights because only bare domain names are processed, without identifiers, by a provider that does not build profiles. If you object to these lookups, contact us; removing the app stops all such requests.

---

## 6. International data transfers

Mozilla Corporation and Duck Duck Go, LLC are based in the United States. Both companies state that they participate in the EU–U.S. Data Privacy Framework (EU adequacy decision), and both offer Standard Contractual Clauses as described in their respective privacy notices. Please refer to their policies for details; they act as independent controllers of the data described above.

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

Because the developer holds **no data about you** (nothing ever reaches him), most requests concern data held by **Apple, Google, Mozilla, or DuckDuckGo** — please direct such requests to those companies. Anything genuinely local to the app you control yourself: sign out or delete the app.

Questions or objections: Support@Kjell.cc

---

## 10. Children

Z-Sync is a general-audience utility and does not knowingly process data of children under 16. It has no user-generated content, chat, or sharing features.

---

## 11. Security measures

- All network traffic uses TLS (HTTPS); plaintext connections to third parties are rejected by the platform's default transport security.
- Sync payloads are end-to-end encrypted client-side before upload (section 5.1).
- Long-lived secrets are held in the iOS Keychain (*ThisDeviceOnly*) or Android Keystore-backed encrypted preferences.
- The iOS app ships a privacy manifest declaring no tracking (Apple `PrivacyInfo.xcprivacy`).
- Minimal attack surface: zero third-party analytics/ad SDKs compiled into the app.

---

## 12. Changes to this policy

If the app's behaviour changes (e.g. new third-party services), this policy will be updated and the version date above will change. Material changes will be reflected in the store release notes.

