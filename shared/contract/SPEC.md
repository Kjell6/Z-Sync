# Z-Sync Cross-Platform Sync Contract

**Contract-Version: 1**

This document plus the golden JSON fixtures under `shared/contract/fixtures/`
(`crypto/`, `wire/`, `auth/`) are the canonical, platform-neutral contract for
Z-Sync's Firefox Sync interoperability: the desktop `spaces` collection wire
format (the collection Zen Browser writes), its PICL cryptography, Hawk request
signing, and the Sync storage API subset both apps use. `ios/` and `android/`
inherit this contract. Both implementations MUST satisfy every fixture.

The key words MUST, MUST NOT, REQUIRED, SHALL, SHALL NOT, SHOULD, SHOULD NOT,
RECOMMENDED, MAY, and OPTIONAL in this document are to be interpreted as described in
RFC 2119.

**Ownership and inheritance.** The contract is owned by the repository, not by a
platform. Proposals change this file and the fixtures first, then the platform
implementations. Platform folders MUST NOT carry forked copies of these rules. `shared/`
is specification and fixtures only: never executable code, platform sources, or
generated files.

**Fixture format.** Every fixture is one JSON file and has a top-level
`"contract": 1` and an `"id"` equal to its basename without `.json`. Single-case
fixtures use:

```json
{ "contract": 1, "id": "<basename>", "purpose": "…", "input": { … }, "expect": { … } }
```

Multi-case fixtures use:

```json
{ "contract": 1, "id": "<basename>", "cases": [ { "id": "…", "input": { … }, "expect": { … } } ] }
```

`input` is always the literal wire/API input, never a platform-internal model.
`expect` uses canonical field names only (wire names plus the derived predicates
defined here, e.g. `isNormalTab`, `decodesAs`). Fixtures are known-answer vectors:
an implementation conforms only if it reproduces `expect` exactly.

---

## 1. Scope and terminology

Scope: reading and writing Zen's `spaces` Sync collection, the `prefs` record that
carries `zen.spaces-sync.normal-tabs`, the `meta/global` write gate (§7.5), the
`crypto/keys` collection-key bootstrap, the
PICL/FxA key derivations behind them, Hawk authentication for Sync storage requests,
and BSO id/URL encoding.

Not in scope (see §8): the local snapshot cache file format, UI strings, demo content,
favicon handling, threading and log wording.

Terminology:

- **Record (WBO/BSO):** one server-side storage object with an `id` and a `payload`
  (the encrypted envelope string).
- **Cleartext:** the decrypted JSON value of a record, `{id, kind, data}` or a
  tombstone `{id, deleted:true}`.
- **Envelope:** the `{ciphertext, IV, hmac}` JSON object stored inside `payload`.
- **kB:** the 32-byte Firefox Sync master key.
- **Space / tab / folder / split:** the record kinds defined in §3.
- **Dot:** one gradient color definition inside a space theme; the canonical decoded
  form of a theme color.
- **Essentials:** the layout's per-container-bucket essential-tab lists.
- **Normal (unpinned) tab:** a tab whose wire `pinned` flag is `false`; it is synced
  only when `zen.spaces-sync.normal-tabs` is on.

---

## 2. Record envelope and dropping rules

Every decrypted `spaces` record is a JSON object:

| field     | required | type    | notes                                                        |
|-----------|----------|---------|--------------------------------------------------------------|
| `id`      | yes      | string  | Equals the BSO id.                                           |
| `kind`    | yes      | string  | One of `container`, `space`, `tab`, `folder`, `split`, `layout`. |
| `data`    | yes      | object  | Kind-specific payload (§3).                                  |
| `deleted` | no       | boolean | Tombstone marker. MUST be a JSON boolean when present.       |

Dropping rules — a record MUST be ignored (never applied, never surfaced) when any of
these holds:

1. `deleted` is the JSON boolean `true` (tombstone). The string `"true"` is **not** a
   tombstone (`wire-deleted-string`).
2. `kind` is missing, not a string, or not one of the six known kinds
   (`wire-ignored-records`: `future-kind`, `record-missing-kind`).
3. `kind` is `container`. Container records are recognized but contractually never
   applied (`wire-ignored-records`: `container-kind`).
4. `data` is missing or not a JSON object.
5. The kind's required fields are missing or of the wrong type
   (`wire-ignored-records`: `tab-missing-required-fields`; `wire-space-numeric-uuid`).
6. The record fails to parse for any other reason (hostile input is dropped, never
   guessed).

Optional fields of the wrong type decode to `nil`/absent unless a rule below says
otherwise; one bad optional field MUST NOT drop the record.

**Tombstones uploaded.** A delete is uploaded as an encrypted cleartext
`{"id": <id>, "deleted": true}` through the normal PUT path (§7). A raw WBO-level
`deleted` with no ciphertext is not a substitute: the desktop reads `deleted` from the
decrypted cleartext.

---

## 3. Per-kind schemas

### 3.1 Common decoding semantics

- **Strings:** a required string field MUST be a JSON string with the stated
  non-empty constraint. Numbers and booleans are not coerced (`wire-space-numeric-uuid`).
- **Optional strings:** a present non-string value (or `null`) decodes to absent.
- **String arrays:** entries that are not strings are dropped, not fatal
  (`wire-layout-basic`).
- **Boolean flag tolerance:** flag fields (`essential`, `pinned`) accept a JSON
  boolean, or the strings `"true"`, `"false"`, `"1"`, `"0"`. Numeric `1`/`0` is the
  tested form for the prefs bool (§7) and implementations SHOULD accept it for record
  flags too. Any other value (e.g. `"perhaps"`) decodes to absent — there is no
  truthiness coercion (`wire-tab-pinned-string-false`, `wire-prefs-normal-tabs`).
  Plain JSON booleans are the only contractual form for `hasStaticIcon` and
  `defaultContainer`.
- **Unknown fields** MUST be ignored, including unknown members inside a folder's
  `live` object (§3.5.1, `wire-folder-live-object`).

### 3.2 Theme (`space.data.theme`)

| field            | required | notes                                                            |
|------------------|----------|------------------------------------------------------------------|
| `type`           | no       | string, e.g. `gradient`, `solid`.                                |
| `gradientColors` | no       | array of color forms (below).                                    |
| `opacity`        | no       | JSON number.                                                     |
| `texture`        | no       | JSON number.                                                     |
| `lightness`      | no       | JSON number.                                                     |

Color forms inside `gradientColors`:

1. **Hex string:** `"#rgb"`, `"#rrggbb"`, or `"#rrggbbaa"`.
2. **RGB array:** `[r, g, b]` byte values 0–255 (`wire-space-rgb-dots`).
3. **Dot object:** `wire-space-object-dots`:

   | field       | required | notes                                                          |
   |-------------|----------|----------------------------------------------------------------|
   | `c`         | no       | hex string, `rgb(...)` string, or `[r,g,b]` byte array.        |
   | `isCustom`  | no       | JSON boolean, default `false`.                                 |
   | `isPrimary` | no       | JSON boolean, default `false`.                                 |
   | `algorithm` | no       | string.                                                        |
   | `lightness` | no       | JSON number or numeric string (`"60"` → `60`).                 |
   | `position`  | no       | object `{x, y}` of JSON numbers.                               |
   | `type`      | no       | string.                                                        |

Canonical decoded form: `gradientColors` is the list of dot hex colors (`#rrggbb`,
lowercase), absent/empty when there are no decodable dots; each dot exposes `hex`,
`isCustom`, `isPrimary`, `algorithm`, `lightness`, `positionX`, `positionY`, `type`.
Hex conversion rounds `channel * 255` and clamps channels to 0…1.

An unrecognized `c` inside a dot object falls back to gray (`#808080`) on iOS and
drops the theme on Android; see §9.

### 3.3 `space` (`wire-space-basic`, `wire-space-object-dots`, …)

| field           | required | notes                                                       |
|-----------------|----------|-------------------------------------------------------------|
| `uuid`          | yes      | non-empty JSON string.                                      |
| `name`          | no       | string.                                                     |
| `icon`          | no       | string.                                                     |
| `theme`         | no       | §3.2.                                                       |
| `containerGuid` | no       | string; `null`/absent means the default container.          |
| `children`      | no       | string array in sidebar order; non-strings dropped.        |

### 3.4 `tab` (`wire-tab-*`)

| field            | required | notes                                                          |
|------------------|----------|----------------------------------------------------------------|
| `tabId`          | yes      | non-empty JSON string.                                         |
| `url`            | yes      | non-empty JSON string.                                         |
| `title`          | no       | string.                                                        |
| `icon`           | no       | string.                                                        |
| `containerGuid`  | no       | string.                                                        |
| `essential`      | no       | boolean flag.                                                  |
| `pinned`         | no       | boolean flag. **Absent/`null`/`true` = pinned; `false` = normal.** |
| `workspaceUuid`  | no       | string; the owning space uuid.                                 |
| `folderId`       | no       | string; the owning folder, `null`/absent = space root.         |
| `staticLabel`    | no       | string.                                                        |
| `hasStaticIcon`  | no       | JSON boolean.                                                  |
| `defaultContainer` | no     | JSON boolean.                                                  |

Derived: `isNormalTab == (pinned == false)`. Never infer pinning from `children`
membership — the wire flag is the source of truth. Normal tabs are gated by the
`zen.spaces-sync.normal-tabs` pref (§7).

### 3.5 `folder` (`wire-folder-basic`, `wire-folder-live-object`, `wire-folder-live-github`, `wire-folder-live-shapes`, `wire-folder-missing-folderid`)

| field            | required | notes                                  |
|------------------|----------|----------------------------------------|
| `folderId`       | yes      | non-empty JSON string.                 |
| `name`           | no       | string.                                |
| `icon`           | no       | string.                                |
| `workspaceUuid`  | no       | string; the owning space uuid.         |
| `parentFolderId` | no       | string; nested folder parent.          |
| `live`           | no       | object; live-folder metadata, §3.5.1.  |
| `children`       | no       | string array.                          |

**Hostile case.** A folder-kind record without `folderId`
(`wire-folder-missing-folderid`) is still folder-shaped for matching purposes
(`decodesAsFolder: true`) but MUST NOT match any target-folder request — neither a
nil target nor a named one.

**Target-folder matching (D2).** When adding a tab to a requested
`(spaceId, folderId)`:

1. A folder target requires the requested `folderId` to be non-null and non-empty.
   A nil target never matches a folder.
2. The candidate record MUST have `kind == "folder"` and `data.folderId` as a
   non-empty JSON string exactly equal to the requested id.
3. A candidate whose `folderId` is missing, `null`, empty, or non-string never
   matches — neither for a nil target nor for a named one.
4. The matched folder's `workspaceUuid` MUST equal `spaceId`; otherwise fall back to
   the space root so a tab never lands in an unrelated folder.

### 3.5.1 `live` folder metadata (`wire-folder-live-object`, `wire-folder-live-github`, `wire-folder-live-shapes`)

A folder record MAY carry a `live` object. Its presence marks the folder as a live
folder. Zen generates a live folder's items on the device that owns it and syncs only
the provider configuration, never the items themselves.

| field        | required | notes                                                        |
|--------------|----------|--------------------------------------------------------------|
| `type`       | no       | non-empty string provider id, e.g. `rss`, `github`.           |
| `state`      | no       | JSON object; provider configuration.                          |
| `state.url`  | no       | string; the provider's source URL.                            |

Canonical decoded form:

- `isLiveFolder` — `true` exactly when `data.live` is a JSON object; `null`, a
  string, an array or a number decode as "not a live folder"
  (`wire-folder-live-shapes`).
- `liveType` — the trimmed non-empty `type` string, otherwise absent. Unknown
  provider ids are kept unchanged.
- `liveSourceURL` — `state.url` when it is an absolute `http` or `https` URL with a
  host; otherwise absent. `http` sources are valid (Zen's own fetcher accepts
  them). Values such as `javascript:…`, relative paths or non-strings never decode
  to a source.

`live` never changes where the folder sits or how it looks in the list: it stays an
ordinary folder record (name, icon, children). The metadata only describes how Zen
generates the folder's items, so a client MAY use it for a provider label or a link
to the source inside the expanded folder. It MUST NOT invent items from it.

Unknown members inside `live` and `state` MUST be ignored; they are provider state
this contract does not model (for example `maxItems`, `timeRange`, `repos`).

**Items are not part of the contract.** Zen excludes the tabs a live folder
generates from `tab` records and from the folder's `children`, so a client MUST
NOT expect them. A reader MUST NOT synthesize or upload them, and MUST NOT read an
empty `children` list as a deleted or broken folder. Writes MUST preserve `live`
unchanged (§7.5.5).

### 3.6 `split` (`wire-split-basic`, `wire-split-normal-pinned-false`)

| field           | required | notes                                                         |
|-----------------|----------|---------------------------------------------------------------|
| `splitId`       | yes      | non-empty JSON string.                                        |
| `gridType`      | no       | string, e.g. `vsep`, `grid`.                                  |
| `pinned`        | no       | boolean flag; absent/`null`/`true` = pinned, `false` = normal. |
| `tabs`          | no       | string array of member tab ids, order preserved.              |
| `workspaceUuid` | no       | string.                                                       |
| `folderId`      | no       | string.                                                       |

Derived: `isNormalSplit == (pinned == false)`. Split member tabs MUST NOT resurface as
individual unplaced tabs; the group occupies the slot.

### 3.7 `layout` (`wire-layout-basic`)

| field        | required | notes                                                             |
|--------------|----------|-------------------------------------------------------------------|
| `spaces`     | no       | string array; space display order. Non-strings dropped.            |
| `essentials` | no       | object mapping container bucket → string array. Non-strings dropped. |

A malformed (non-array) bucket MUST be dropped without discarding valid buckets (§9
records the current platform divergence). Missing or empty `essentials` is
equivalent to none.

### 3.8 `container`

Recognized for forward compatibility, never applied. Any record with
`kind == "container"` is dropped.

---

## 4. Cryptography (PICL / FxA / oldsync)

Primitives: HMAC-SHA256, SHA-256, and HKDF-SHA256 as specified by RFC 5869.

**HKDF salt.** All derivations use an empty salt. Implementations MUST treat an
empty/absent salt as `HashLen` (32) zero bytes, exactly as RFC 5869 §2.2 prescribes;
this is byte-identical to CryptoKit's `HKDF(salt: Data())` and Android's 32-byte
default (`crypto-hkdf-rfc5869-case1`, `crypto-sync-key-bundle-kb-zero`).

**Namespace.** `identity.mozilla.com/picl/v1/`; the HKDF `info` is
`namespace + name`, UTF-8 (`identity.mozilla.com/picl/v1/<name>`).

**Sync key bundle (`syncKeyBundle`).** From `kB`:

- `info = "identity.mozilla.com/picl/v1/oldsync"`, length 64.
- `encryptionKey = material[0..32]`, `hmacKey = material[32..64]`.
- Fixture: `crypto-sync-key-bundle-kb-zero`.

**Token material (`tokenMaterial`).** From a token and a type (e.g.
`sessionToken`, `keyFetchToken`):

- `info = "identity.mozilla.com/picl/v1/<type>"`, length 96.
- `id = hex(material[0..32])` → Hawk id; `authKey = material[32..64]` → Hawk key;
  `bundleKey = material[64..96]` → OAuth token bundling.
- Fixture: `crypto-token-material-session-token`.

**Client state (`clientStateBytes`).** `SHA-256(kB)[0..16]`, rendered for the token
server as unpadded base64url. Fixture: `crypto-client-state-bytes-kb-zero`.

**Unbundle.** For `payload = ciphertext || tag` (32-byte trailing tag), namespace
`name`, bundle key `bundleKey`:

- `material = HKDF(bundleKey, namespace + name, 32 + len(ciphertext))`.
- `hmacKey = material[0..32]`, `xorKey = material[32..]`.
- Verify `HMAC-SHA256(hmacKey, ciphertext) == tag` (constant-time compare), then
  `plaintext = ciphertext XOR xorKey`.
- Reject `payload` shorter than 32 bytes.
- Fixture: `crypto-unbundle-account-keys`.

**BSO envelope.** The encrypted record payload is a JSON object with exactly these
field names and types:

| field        | type   | notes                                      |
|--------------|--------|--------------------------------------------|
| `ciphertext` | string | standard base64 of the AES-256-CBC output. |
| `IV`         | string | standard base64 of the 16-byte IV. Two capital letters, exactly `IV`. |
| `hmac`       | string | lowercase hex of HMAC-SHA256.              |

Rules:

- The HMAC is computed over the **base64 ciphertext string bytes** (UTF-8), not over
  the raw ciphertext.
- Encryption/decryption is AES-256-CBC with PKCS#7 padding.
- Decryption MUST verify the HMAC before decrypting; a mismatch raises
  `bso hmac mismatch`. Invalid base64 raises `bso base64`. HMAC comparisons are
  constant-time on both platforms.
- Envelope geometry is validated before decryption: an `IV` that does not decode to
  exactly 16 bytes raises `bso iv length`; a `ciphertext` that is empty or not a
  multiple of the 16-byte block size raises `bso ciphertext length`.
- Fixtures: `crypto-bso-envelope-valid`, `crypto-bso-envelope-tampered-hmac`.

Error strings shared by both platforms: `invalid hex`, `xor length mismatch`,
`bundle too short`, `bundle hmac mismatch`, `bso hmac mismatch`, `bso base64`,
`bso iv length`, `bso ciphertext length`.

---

## 5. Hawk authentication

**Normalized preimage.** Exactly ten lines joined with `"\n"` (so the string ends with
the separators for the three trailing empty lines):

1. `hawk.1.header`
2. Unix timestamp (seconds, decimal)
3. nonce
4. HTTP method, uppercased
5. resource — the **verbatim** request-line path and query, percent-encoding preserved
6. host, lowercased
7. port (decimal; default 443 for https, 80 for http)
8. payload hash, or the empty string when there is none
9. `ext` — always empty
10. always empty (trailing line)

Example for `hawk-authorization-resource`:

```
hawk.1.header
1700000000
nonce
PUT
/1.5/1/storage/spaces/%7Babc%7D
sync.example.com
443


```

**MAC.** `base64(HMAC-SHA256(key, UTF-8(normalized)))`.

**Authorization header.**
`Hawk id="…", ts="…", nonce="…", mac="…"`, in that order. Append `, hash="…"` only
when a payload hash is present. Fixture: `hawk-authorization-resource`.

**Payload hash.**
`base64(SHA-256("hawk.1.payload\n" + normalizedContentType + "\n" + body + "\n"))`,
where `normalizedContentType` is the content type truncated at the first `;` and
trimmed. Fixture: `hawk-payload-hash`.

**Nonce.** 8 random bytes, base64, with `+`, `/`, and `=` removed. Tests use a fixed
nonce; determinism is required only for vectors, never at runtime.

**Verbatim resource rule.** Signing MUST use the exact percent-encoded resource from
the request line. Do not derive it from a URL parser that decodes escapes — on iOS
`URL.path` turns `%7B` back into `{` and breaks the MAC. Call sites pass `resource`
explicitly; see §9 for the platform difference.

---

## 6. BSO ids and URLs

The record id is an opaque server-side string. For the request line it MUST be
percent-encoded over its UTF-8 bytes with exactly the RFC 3986 unreserved set
`A–Z a–z 0–9 - . _ ~`; every other byte becomes `%XX` with **uppercase** hex digits.
Plain ids pass through unchanged. Fixture: `bso-ids-percent-encoding`.

Examples: `{ebaf893e-6ab2-4dbe-a336-62beb0c2b962}` →
`%7Bebaf893e-6ab2-4dbe-a336-62beb0c2b962%7D`; `layout` → `layout`;
`1787333003647-5` → `1787333003647-5`.

Storage URLs use `/storage/<collection>/<encodedBSOId>` for PUT and
`/storage/<collection>?…` for GET.

---

## 7. Sync API usage

**Collections.** Spaces live in `spaces`. The single `prefs` record carries the synced
preferences as `{"value": {…}}`:

- `zen.spaces-sync.normal-tabs` (bool): the normal-tabs opt-in. When the record is
  absent or unreadable, callers default to `true`.
- `zen.workspaces.separate-essentials` (bool, optional): Zen 1.23 and later register
  this pref for sync. Firefox's prefs engine writes `null` for a pref the user never
  changed, so the key is present with a `null` value until the default is touched.
  See §7.4.

Collection keys are read from `crypto/keys`.

**Normal-tabs capability (write gating).** The read default above applies to display
filtering only. Write flows that can author a `pinned:false` tab MUST use the derived
`normalTabsCapability` predicate (`wire-prefs-normal-tabs-capability`), which
distinguishes "the browser supports normal-tab syncing" from "the option is on":

| value      | condition                                                                 | meaning                                                            |
|------------|---------------------------------------------------------------------------|--------------------------------------------------------------------|
| `enabled`  | prefs record present, key present, value parses true (§3.1 bool tolerance) | version supports it and the user turned it on                       |
| `disabled` | prefs record present, key present, value parses false/`null`/unparseable   | version supports it (Zen registers the pref for sync only in versions that sync normal tabs), but the option is off |
| `absent`   | no readable prefs record, or key missing                                   | support is not proven (older Zen, or prefs sync unavailable)        |

A client that would write a `pinned:false` tab while the capability is not `enabled`
MUST fall back to a pinned record: normal records are held back, not tombstoned, while
the option is off (see "Normal-tabs gating" below), so the tab would otherwise be
invisible on every device. A `pinned:false` tab or split record observed in `spaces`
proves browser support (records linger after the option is turned off), so
implementations SHOULD report `disabled` rather than `absent` in that case.

**Full read.** `GET /storage/spaces?full=1&limit=2500`, following the
`X-Weave-Next-Offset` response header for subsequent pages
(`&offset=<encoded>`, same unreserved encoding), up to **50 pages**. Pagination stops
when the server signals no next offset, the page is empty, or the next offset equals
the previous one (stuck-token guard).

**Write.** `PUT /storage/spaces/<encodedBSOId>` with body
`{"payload": "<envelope JSON string>"}` where the payload is the §4 envelope string
for the encrypted cleartext record. Deletes PUT a tombstone cleartext (§2).

**Collection keys bootstrap.** `GET /storage/crypto/keys`. If it has no `payload`,
create it with `PUT /storage/crypto/keys` carrying an envelope for
`{"default": [<encB64>, <hmacB64>], "collections": {…}}` with fresh random 32-byte
keys, then re-read. Otherwise decrypt the payload with the sync key bundle (§4) and
index per-collection key bundles, falling back to `default`.

**Token server.** `GET https://token.services.mozilla.com/1.0/sync/1.5` with:

- `Authorization: BrowserID <assertion>`.
- `X-KeyID: <keysChangedAt>-<unpadded base64url(clientStateBytes(kB))>` (the legacy
  oldsync scope uses `keysChangedAt` verbatim, normally `0`).

The response provides `uid`, `api_endpoint`, `id`, `key`, and `duration`; `id`/`key`
are the Hawk credentials used for all Sync storage requests.

**Endpoint validation.** Clients MUST reject a non-HTTPS token-server `api_endpoint`
with the error string `insecure sync endpoint` before signing or sending any request.
Redirects MUST NOT be followed for Sync storage or auth-server requests.

**Normal-tabs gating.** Records with `isNormalTab`/`isNormalSplit` are hidden when the
pref is off, but are not tombstoned; they reappear when the option returns.

### 7.1 FxA auth error-body mapping (`auth-errno-103`)

FxA endpoints that return HTTP >= 400 with a JSON error body map to error kinds:

- `errno` `103` (two-step authentication enabled) MUST map to the **totpRequired**
  error kind so callers can prompt for a TOTP code instead of treating it as a
  generic sign-in failure.
- Any other `errno` MUST map to the plain **auth** error kind, carrying the body's
  `message` (falling back to `error`, then to `HTTP <status>`).
- Statuses below 400 MUST NOT map to an error.

### 7.2 Conditional writes and conflict handling

Writes MAY carry a condition on the target resource's server-side timestamp via the
`X-If-Unmodified-Since` request header. The header value is a decimal server
timestamp string in the same format as `X-Last-Modified` (e.g. `"1700000000.00"`).

- **Semantics.** The server compares the header value with the target's current
  timestamp. If they differ, the write is rejected with **412 Precondition Failed**,
  an **empty body**, and an `X-Last-Modified` response header carrying the target's
  current timestamp. On success the response `X-Last-Modified` carries the new
  timestamp.
- **Resource granularity.** The condition applies to the timestamp of the addressed
  resource. A BSO URL (`/storage/<collection>/<encodedBSOId>`) conditions on that
  BSO's timestamp; a collection URL (`POST /storage/<collection>`) conditions on the
  collection's timestamp.
- **Missing timestamp = 0 (create-if-absent).** A BSO with no server timestamp is
  treated as timestamp `0`, so `X-If-Unmodified-Since: "0"` creates the record
  only if it does not already exist; if it exists, the server responds 412.
- **Multi-record POST.** `POST /storage/<collection>` with
  `X-If-Unmodified-Since` conditions the whole request on the collection timestamp.
  There is no per-BSO condition inside the POST body: every record in one POST
  shares the single collection-level condition. The condition is all-or-nothing (a
  412 writes no record), but the records are not: the server validates and stores
  each one separately, so a 200 can apply some records and reject others. The
  response body reports the per-record outcome:
  `{"modified": <timestamp>, "success": [<id>, …], "failed": {<id>: <reason>, …}}`.
  A requested id that is in `failed` or missing from `success` was not written.
- **Expected client behavior.** On 412 the client SHOULD re-read the target (GET for
  a collection, GET/HEAD for a BSO), merge the server state, retry the write
  **once** with the fresh `X-Last-Modified` value as the new condition, and surface
  a conflict to the caller if the retry also fails. The retry MUST NOT loop. A
  partial POST outcome is handled the same way: re-read, recompute the same
  semantic edit against the fresh state, and retry once. Edits MUST be idempotent
  for this to be safe (reuse the same new record id, union rather than append).
  A retry that finds its edit already half-applied (some records stored, others
  rejected) MUST complete the missing records instead of treating the fresh
  state as a no-op.
- **Collection without a timestamp.** When the read that plans a conditional POST
  returns no `X-Last-Modified` (for example a 404 on a collection that does not
  exist yet), the client MUST send `X-If-Unmodified-Since: "0"` rather than drop
  the condition.
- **Legacy unconditional writes.** Omitting the header performs an unconditional
  write, exactly as before this section. Servers MUST continue to accept it and
  clients MUST NOT require conditional support; conditional writes are an additive,
  non-breaking optimization.

### 7.3 Recorded HTTP exchanges

`shared/contract/http/` holds recorded request/response exchanges for the Sync
storage subset in §7. They are flat, one JSON file per recording, versioned with the
same top-level `"contract": 1`, and use:

```json
{
  "contract": 1,
  "id": "<basename without .json>",
  "purpose": "…",
  "input": { "request": { "method": "GET", "path": "/storage/spaces", "headers": { … } } },
  "expect": { "response": { "status": 200, "headers": { … }, "body": null } }
}
```

- `id` MUST equal the file's basename without `.json`.
- `input.request.path` is the request-line path after the token server's
  `api_endpoint`, verbatim percent-encoded, including the query string when present.
- `input.request.headers` records only the semantically relevant request headers
  (e.g. `X-If-Unmodified-Since`). Method and path are always present. Hawk
  `Authorization` headers are never recorded because their timestamp, nonce, and MAC
  are request-specific or random; §5 covers Hawk.
- `expect.response.body` is `null` when the response has no body **or** when the body
  contains record/crypto material (BSO lists, envelopes) that the test constructs
  itself; it is a JSON object when the body is plain JSON. Ciphertext MUST NOT be
  embedded in recordings. Structural predicates may accompany `body: null`: the
  `crypto/keys` recordings use `"hasPayload": true` and
  `"collections": ["default"]` to describe an envelope the test builds.
- Recordings are documentation and hermetic test vectors for behavior already
  specified in §7; they are **not** a new wire-format requirement and add no fields
  to the protocol.
- **Systems without conditional-header support.** A system that strips
  `X-If-Unmodified-Since` performs the write unconditionally, which remains
  contract-conformant for the write itself; the client detects the lost condition by
  re-reading and comparing timestamps. A system that rejects the conditional header
  with an HTTP error (e.g. **400**) surfaces that error to the caller: the client does
  not automatically retry the write unconditionally and does not loop on the
  conditional form against that server. Callers MAY instead fall back to unconditional
  writes through the local safe-sync switch (§8), which re-reads to detect conflicts;
  that switch is a non-contractual implementation detail.

### 7.4 Essentials grouping

The layout record always groups essentials by container bucket (§3.7), but Zen
Desktop renders them container-specific or shared depending on the
`zen.workspaces.separate-essentials` pref. Both platforms MUST resolve the same
effective grouping automatically, with no user-facing override:

1. When the `prefs` record carries `zen.workspaces.separate-essentials`, that value
   is decisive. Zen 1.23 and later register the pref for sync; Firefox's prefs
   engine writes `null` for a pref the user never changed while keeping the key.
   Zen's default is `true`, so a present key whose value is `null` or unparseable
   means container-specific (§3.1 bool tolerance; fixture
   `wire-prefs-separate-essentials`).
2. Otherwise the browser does not sync the pref (Zen 1.22 and older, or an
   unreadable `prefs` record): infer container-specific iff at least one essential
   sits in a bucket other than `default`.

Rendering follows desktop `_shouldShowTab`: a space with a container shows its
bucket; a space without one shows the `default` bucket plus buckets no space uses
(orphan containers). Shared grouping shows every essential on every space, merging
buckets `default` first, the rest in stable key order (the wire format orders tabs
within a bucket only). When several buckets are merged, entries MUST additionally be
de-duplicated by URL across buckets, first occurrence winning, because the same site
is commonly stored once per container bucket; a genuine duplicate within a single
bucket is preserved.


### 7.5 Write preconditions

These rules apply to every write to `spaces`, in both the conditional and the legacy
path. A client that cannot meet one MUST refuse the whole write and change nothing.

1. **Engine version gate (`wire-meta-global-write-gate`).** Before writing, read
   `GET /storage/meta/global`. Its `payload` is a plain JSON string, not an envelope.
   Writes are allowed when the record is absent or has no payload. Otherwise the
   payload MUST parse as a JSON object; when it has `storageVersion`, that MUST be the
   number `5`; when it has an `engines` member, that MUST be an object; when
   `engines` has a `spaces` member, it MUST be an object whose `version` is a JSON
   number no greater than `3`, the Zen Spaces engine version this contract
   describes. Anything else blocks writes.
2. **Complete read.** The read that plans a write MUST be complete: every page parsed
   as a JSON array and pagination ended because the server signalled no next offset,
   not because a page cap was hit. A malformed page is an error for reads too; it is
   never treated as an empty page.
3. **Present parent.** The space (or folder) that a new tab attaches to MUST come
   from that read. A client MUST NOT build a parent record from a local cache or any
   other source. If the target space's record is absent or deleted, the write fails
   as "target missing". If it is present but does not decrypt, the write fails as an
   incomplete read.
4. **Removals see everything.** A delete, move or pin rewrites every parent that
   references the id, so it MUST refuse when any record in the planning read fails
   to decrypt.
5. **Preserve what you don't own.** A rewritten record starts from its decrypted
   cleartext. Only the edited `data` fields change; unknown top-level fields and
   unknown `data` fields (for example a folder's `live` object) are written back
   unchanged. When an edited list such as `children` holds non-string entries, the
   string entries are kept in order and the rest are dropped, matching the read rule
   in §3.1.
6. **Key records.** Each `crypto/keys` bundle MUST be an array of at least two
   base64 strings that decode to 32 bytes each; anything else is a crypto error, not
   a crash.

---

## 8. Explicitly non-contractual

The following are implementation details and MUST NOT be treated as part of this
contract or used to infer wire behavior:

- **Local snapshot cache** (`spaces-cache.json` and equivalents): platform internal
  and explicitly exempt from versioning (§10). The platforms already differ: iOS
  `Codable` writes items as `{"tab": {"_0": {…}}}` / `{"split": {"_0": {…}}}`-style
  enum encodings, while Android writes `{"type": "tab", "tab": {…}}`. No fixture
  covers the cache, and no cross-platform compatibility is required.
- UI strings and copy, including error presentation.
- `DemoCatalog` sample content.
- Favicon fetching/selection and caching.
- Threading, scheduling, retry cadence, logging and log wording.
- Header values beyond those pinned by §4–§7 (e.g. `User-Agent`).
- The app-level safe-sync switch that decides whether writes use the conditional
  headers of §7.2. It is a local implementation detail and MUST NOT be treated as
  part of the wire contract; §7.2 describes the wire behavior only.

---

## 9. Known leniencies (tolerated until fixed)

These are real divergences today. They are tolerated so both apps can pass the
fixtures, but fixtures do not pin them and future work SHOULD remove them:

1. **Layout non-array bucket.** If a bucket value inside `layout.data.essentials` is
   not an array, Android skips just that bucket while iOS discards `essentials`
   entirely. The contract target is: drop the malformed bucket, keep the others.
2. **Theme dot with unrecognized `c`.** If a dot object's `c` is an unrecognized type
   (e.g. an array of strings), iOS keeps a gray-fallback dot
   (`(0.5, 0.5, 0.5)` → `#808080`) while Android drops the dot/theme. The contract
   target is a gray-fallback dot.
3. **BSO base64 leniency.** Android falls back to a MIME base64 decoder when strict
   decoding fails; iOS is strict. Emitting non-strict base64 is not contractual and
   Android's tolerance is a convenience only.
4. **Hawk derived resource path.** `java.net.URL` keeps percent-escapes in `path`
   while Swift `URL.path` decodes them. Call sites MUST pass the explicit verbatim
   `resource` (§5), which makes both platforms behave identically; relying on each
   URL parser's derived path is tolerated only because explicit resources are always
   supplied.

---

## 10. Versioning and change process

- **Fixture immutability.** Within a major contract version, a published fixture's
  `input` and `expect` MUST NOT change. Corrections are made by adding a new fixture
  (or a new case) and marking the old one obsolete in this document's changelog.
- **Breaking changes** — requiring new fields, changing a field's type/meaning,
  changing crypto, Hawk, or encoding output, or removing tolerance — require a new
  major `contract` value (`2`) and a parallel fixture set. Both platforms are updated
  in lockstep.
- **Non-breaking changes** — clarifying prose, adding fixtures for behavior that is
  already contractual, or widening tolerance — may happen within major 1.
- The local snapshot cache (§8) is exempt: it may change shape without a contract
  version bump, provided each platform still migrates its own old caches.
- Definition of conformance: `ios/` and `android/` both pass every fixture whose
  top-level `contract` equals the version they implement, as verified by each
  platform's test suite reading the shared JSON files.

---

## 11. Changelog

- **2026-10-27 — Essentials grouping resolves automatically.** Zen 1.23
  registers `zen.workspaces.separate-essentials` for sync. §7.4 now resolves the
  grouping from that pref alone — a present `null` value is the untouched default
  `true` — and infers from the layout buckets only when the pref is absent. The
  app-level override is gone. Added fixture `wire-prefs-separate-essentials`.
  Contract-Version stays 1: no wire change, and the resolution only makes §7.4
  precise.
- **2026-10-09 — Write preconditions.** Added §7.5: a `meta/global` engine-version
  gate (fixture `wire-meta-global-write-gate`), complete planning reads, no
  synthesized parents, fail-closed removals on undecryptable records, preservation of
  unknown cleartext fields, and key-record validation. §7.2 now states that a
  multi-record POST is conditioned as a whole but applied per record, that partial
  outcomes are retried once like a 412, that a missing collection timestamp is
  sent as `"0"`, and that a retried edit finding its target half-applied
  completes it rather than reporting a no-op. Contract-Version stays 1: the wire
  format is unchanged and the new rules only make clients refuse unsafe writes.

- **2026-10-04 — Essentials merge de-duplication by URL.** When merging essentials
  buckets for shared display, entries are now de-duplicated by URL across buckets
  (first occurrence wins), while duplicates within one bucket are preserved (§7.4).
  Added fixture `wire-layout-essentials-dup-url`. Contract-Version stays 1: additive
  and non-breaking (identical behavior for the common single-bucket case).
- **2026-09-12 — Normal-tabs capability.** Added the write-gating derived predicate
  (`normalTabsCapability`, §7) plus fixture `wire-prefs-normal-tabs-capability`.
  Contracts 1: additive derived behavior, no wire change; the read default `true`
  remains unchanged.
- **2026-09-12 — Essentials grouping.** Documented how the always-per-container wire
  layout maps to the desktop rendering modes (§7.4): optional
  `zen.workspaces.separate-essentials` in the `prefs` record, wire-bucket inference
  when absent, orphan-container handling, and the shared merge order. Contract-Version
  stays 1: clarifying prose plus an optional pref read.
- **2026-09-12 — Conditional writes and recorded HTTP exchanges.** 27 wire/crypto
  fixtures plus 11 recorded HTTP exchanges under `shared/contract/http/`;
  conditional-write semantics documented (§7.2) and the recording schema defined
  (§7.3). Contract-Version stays 1: additive and non-breaking.
- **2026-09-12 — Contract-Version 1 published.** Initial canonical contract plus 27
  golden fixtures: 16 wire-format vectors (spaces, tabs, folders, splits, layout,
  ignored records, prefs, hostile cases), 9 crypto/Hawk vectors (RFC 5869 HKDF,
  oldsync key bundle, BSO envelope valid/tampered, account-keys unbundle, client
  state, token material, Hawk resource MAC, payload hash), 1 BSO id
  percent-encoding vector and 1 FxA auth error-body mapping (§7.1, errno 103 →
  totpRequired). All crypto/Hawk expected values independently recomputed
  with a Python reference implementation (hashlib/hmac + AES-256-CBC).
