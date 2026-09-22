# Z-Sync for Android

Native Android port of the iOS Z-Sync app (this repo's `ios/ZenCompanion/`,
`ios/Shared/`, `ios/ShareExtension/` Swift sources). Kotlin + Jetpack Compose, no
Material-3 default look — a custom paper/ink/coral theme matching the iOS
app pixel-for-pixel and spring-for-spring.

## What it does

Independent app for browser spaces:

- Sign in with a **Mozilla / Firefox account** via the official accounts page
  in a WebView (WebChannel bridge, same path as Firefox desktop).
- Sync **Spaces** over Firefox Sync 1.5 (Hawk + AES-256-CBC BSOs):
  pinned tabs, folders, essentials, per-space gradients.
- Open URLs in the system browser; share a URL into any space
  (`ShareActivity`, the Android equivalent of the iOS Share Extension).

## Build

Requirements: JDK 17–26 (the Gradle wrapper pins 9.7.1), Android SDK with platform 37.

```bash
cd android
# point local.properties at your SDK or export ANDROID_HOME
./gradlew :app:assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # crypto/decoder/resolver unit tests
```

(If `./gradlew` is missing, run `gradle wrapper --gradle-version 9.7.1` once.)

Package: `de.kjell.zencompanion` · versionName `1.3` · versionCode `6` ·
minSdk 26 · targetSdk 36 · compileSdk 37 · portrait-only.

Toolchain: Gradle 9.7.1 · AGP 9.4.1 · built-in Kotlin (Compose compiler plugin
2.2.10) · Compose BOM 2026.09.00.

## Architecture

```
android/
  app/src/main/java/de/kjell/zencompanion/
    sync/        FxACrypto, HawkAuth, SyncCrypto, SyncClient, FxAClient,
                 SpacesSyncService, ZenSpaces (wire format + snapshot model)
    data/        AccountStore (EncryptedSharedPreferences + Keystore),
                 SnapshotCache (spaces-cache.json), AppEvents
    favicon/     FaviconResolver, FaviconDecoder (largest-ICO-frame),
                 FaviconLoader (memory+disk cache, coalesced, 6-way prefetch)
    ui/
      theme/     Palette (paper/ink/coral/lift), Type (system font)
      motion/    Spring catalog: response r ↔ stiffness (2π/r)², reduce-motion
      components/ ZenMark, ZenIconView, Favicon, gradient engine + noise,
                 folder fold icon, tab rows, essentials grid
      screens/   Landing, Mozilla sign-in WebView, spaces pager + switcher +
                 account bar
      sheets/    Account sheet, history sheet
    share/       ShareActivity + ShareScreen (share-extension parity)
  app/src/test/  Ports of ZenCompanionTests (crypto/Hawk/AES vectors,
                 ZenSpaces decoders, favicon resolver/decoder) — all green
```

## Parity notes

- **Springs**: every SwiftUI `.spring(response:dampingFraction:)` maps to
  Compose `spring(dampingRatio = d, stiffness = (2π/r)²)` — see
  `ui/motion/Motion.kt`. Gradient crossfades are `tween(280, FastOutSlowIn)`
  exactly like the Swift `.easeInOut(duration: 0.28)` dual-slot machine.
- **Essentials pinning** across spaces whose effective essentials grid matches
  (same-container spaces in container-specific mode, all spaces in shared mode)
  uses the same minX math as `SpacePageContainer`
  (`pagerState.getOffsetDistanceInPages * pageWidth`).
- **Folder icon** folds with scale/skew/translate numbers copied from
  `FolderTransformModifier`; back-flap bezier points are verbatim.
- **Secrets**: session token + kB live only in EncryptedSharedPreferences
  (Keystore-backed), excluded from backups. Token-server creds stay memory-
  only and refresh 45 s before expiry.
- **Favicons**: direct sync URL else DuckDuckGo ip3; multi-frame ICOs decode
  their largest frame; ~400-entry memory cache + disk cache + coalesced
  in-flight fetches; placeholder is a plain gray rounded square.
- **Localization**: English-only, matching `Shared/Localizable.xcstrings`.
- Reduce Motion (`Settings.Global.TRANSITION_ANIMATION_SCALE == 0`) snaps all
  springs but keeps layout identical.

## Fonts & icons

- `res/drawable/zen_*.xml` — vector conversions of the Zen Browser sidebar
  icon set from the iOS asset catalog (`Icons from Zen Browser under MPL 2.0`,
  same attribution string as iOS).
- `ic_xmark/ic_checkmark/ic_arrow_clockwise/…` — hand-drawn stroked SF-style
  toolbar glyphs (icons-only toolbars, TalkBack labels from string resources).
