# Releases

Store releases are maintainer-only: nothing here bumps a version, a build
number, or uploads anything on its own. This file records **what is currently
shipped and how to check it**, so any agent can answer "what does TestFlight
have?" without guessing.

## Current state

| Platform | Channel    | Version | Last build |
| -------- | ---------- | ------- | ---------- |
| iOS      | App Store  | 1.5     | released   |
| iOS      | TestFlight | 1.6     | 11         |

Keep this table current when a build ships. iOS is the only platform using
TestFlight; Android releases go through Google Play, shipped by the maintainer.

## Where the version lives

`ios/project.yml` is the source of truth: `MARKETING_VERSION` is the marketing
version, `CURRENT_PROJECT_VERSION` the last known build. Both are mirrored into
`ios/ZenCompanion.xcodeproj/project.pbxproj` — keep the two in sync (edit the
pbxproj values by hand; a full `xcodegen` run rewrites every object id).

The upload script asks App Store Connect for the next free build number and
passes both values to `xcodebuild` as overrides, so a stale committed build
number cannot collide. A stale marketing version, though, silently proposes the
wrong release: if a dry run offers a version other than the one in the table
above, fix the repo instead of passing an override.

## Shipping an iOS build to TestFlight

The pipeline lives outside this repo, on the maintainer's machine:

```sh
cd ios
~/.appstoreconnect/testflight.sh --dry-run   # app, version, next build number, group
~/.appstoreconnect/testflight.sh
```

The script archives, exports, uploads, waits for Apple's processing to reach
`VALID` and assigns the build to the beta group. Apple processing takes roughly
2–15 minutes. It refuses to run on an unclean working tree; commit the work
first (or pass `--dirty` only when the maintainer explicitly wants the working
state on a device).

## Demo access for App Review and Play

Reviewers cannot sign in with a Mozilla account that holds Zen data, so the app
has a hidden sample-data mode. On the signed-out home, tap the title **"Your
Zen spaces appear here"** five times quickly (at most two seconds between taps).
A haptic confirms it and the home fills with sample spaces under the banner
"Sample data — not connected to a Mozilla account". Settings has "Exit sample
data" to leave it. iOS and Android work the same way.

Before each store submission, check that the review notes in App Store Connect
(App Review Information → Notes) and the Play Console app-access instructions
still describe this. Notes written before the signed-out home was redesigned
may point at the old sign-in card and its app mark, which no longer exist.

## Checking what is actually live

```sh
~/.appstoreconnect/asc.py builds 6804470330 1.6   # builds for a version, with state
~/.appstoreconnect/asc.py testers fa681e68-7a0f-4743-a085-7de203781a66   # group Internal
```

`6804470330` is the App Store Connect app id of Z-Sync
(`de.kjell.zencompanion`). Use `asc.py apps` when it is unknown.

## Default browser on iOS

**iOS cannot be selected as the default browser yet.** Apple only allows it for
apps that hold the `com.apple.developer.web-browser` entitlement, which the
developer account has to request; until it is granted, declaring `http`/`https`
URL types has no effect and the app is not offered as a browser. The code for
receiving a link is already in place, so the remaining work is configuration
and review.

Z-Sync's own link form works today, without the entitlement:
`zsync://open?url=<percent-encoded address>`, e.g.
`zsync://open?url=https%3A%2F%2Fexample.com%2Fpage`. In Shortcuts, use an
"Open URLs" action whose URL is `zsync://open?url=` followed by the
URL-encoded address. Nothing else is accepted: another host or path, a missing
or extra parameter, and any target that is not `http`/`https` with a host are
ignored silently.

Remaining maintainer steps, in order:

1. Request the entitlement from Apple:
   <https://developer.apple.com/contact/request/default-browser-entitlement/>.
   It is tied to the app's bundle id (`de.kjell.zencompanion`) and the team.
2. Add `com.apple.developer.web-browser` (Boolean, `true`) to the app's
   entitlements — both `ios/ZenCompanion/ZenCompanion.entitlements` and the
   `entitlements.properties` block in `ios/project.yml`, so a later `xcodegen`
   run keeps it. Re-issue the provisioning profile afterwards.
3. Add the `http` and `https` URL schemes to `CFBundleURLTypes` in
   `ios/project.yml` and `ios/ZenCompanion/Info.plist`. `HomeView` already
   routes `onOpenURL` links into the in-app browser. The app's own `zsync`
   scheme is declared in both files already.
4. Check Apple's requirements for default-browser apps before submitting:
   - **A text field for a URL on launch, or search tools.** Met: the app opens
     on the home screen, whose action bar has a search field, signed in or out;
     one tap opens the address field. With "Open into search" on, that search
     tab comes up at launch.
   - **Navigate directly to the URL it is given.** Met: an `http`/`https` link
     arriving through `onOpenURL` becomes a normal page in the in-app browser.
     A link the app itself sends out is recognised when iOS hands it back and
     opens in-app instead of being sent out again.
   - **No redirects to unexpected locations.** Met: only `http`/`https` links
     from another app are accepted, the address is opened like a typed one, and
     it is not rewritten or handed to another app.
5. Add a Settings row that opens the app's page in the Settings app, where the
   reader can pick the default browser (`UIApplication.openSettingsURLString`).

Until steps 1–3 are done, Z-Sync is not a selectable browser on iOS; only its
own `zsync://open?url=` links reach it.

## Related

- Signing: the team id lives in the gitignored `ios/Config/Team.local.xcconfig`
  (see the `.example` next to it). A fresh worktree does not have it — copy it
  from the main checkout before building for a device.
- Waiting is not a step: the upload script blocks until the build is assigned,
  so there is no need to poll App Store Connect afterwards.
