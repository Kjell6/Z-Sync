# AGENTS.md

## Repo layout

- `ios/` — Z-Sync iOS app (XcodeGen: `ios/project.yml` → `ios/ZenCompanion.xcodeproj`, regenerate with `xcodegen` from inside `ios/`). App sources live in `ZenCompanion/{App,Spaces,Browser,Settings,SignIn,Activity}/`, shared code in `Shared/{Sync,Data,UI}/`, tests in `ZenCompanionTests/{Contract,App}/`. Also `ShareExtension/`, `scripts/`.
- `android/` — Android app (Kotlin + Jetpack Compose, Gradle). Feature packages under `app/src/main/java/de/kjell/zencompanion/` (`sync/`, `data/`, `ui/`, `share/`, `favicon/`).
- `shared/` — cross-platform sync contract only: `shared/contract/SPEC.md` plus golden JSON under `shared/contract/fixtures/{crypto,wire,auth}/` and `shared/contract/http/`. Never place executable code, platform sources, or generated files here.
- Platform code stays inside its platform folder. The only exception is `shared/`, which is spec + fixtures, not code. Display name is **Z-Sync**. Xcode/Gradle module and bundle id still use `ZenCompanion` / `de.kjell.zencompanion` (App Store identity).
- Canonical privacy text: `docs/privacy.md`. Apple Team ID lives in gitignored `ios/Config/Team.local.xcconfig` (see the `.example` next to it).

## Language

The whole app is English-only. Never add or keep localizations (de, fr, …) — all strings are English regardless of the device locale.

## Testing

Run the full test suite once at the end of a module (or at the very end of the work), not after every intermediate step. Intermediate work should implement and may do a cheap compile check when useful, but must not block on full test runs. Report test results from the consolidated run.

## Signing and stores

Do not commit keystores, Team IDs, `.p8` keys, or store credentials. Do not upload to App Store Connect or Google Play. Store releases are maintainer-only.
