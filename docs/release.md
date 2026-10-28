# Releases

Store releases are maintainer-only: nothing here bumps a version, a build
number, or uploads anything on its own. This file records **what is currently
shipped and how to check it**, so any agent can answer "what does TestFlight
have?" without guessing.

## Current state

| Platform | Channel    | Version | Last build |
| -------- | ---------- | ------- | ---------- |
| iOS      | App Store  | 1.5     | released   |
| iOS      | TestFlight | 1.6     | 9          |

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

## Checking what is actually live

```sh
~/.appstoreconnect/asc.py builds 6804470330 1.6   # builds for a version, with state
~/.appstoreconnect/asc.py testers fa681e68-7a0f-4743-a085-7de203781a66   # group Internal
```

`6804470330` is the App Store Connect app id of Z-Sync
(`de.kjell.zencompanion`). Use `asc.py apps` when it is unknown.

## Related

- Signing: the team id lives in the gitignored `ios/Config/Team.local.xcconfig`
  (see the `.example` next to it). A fresh worktree does not have it — copy it
  from the main checkout before building for a device.
- Waiting is not a step: the upload script blocks until the build is assigned,
  so there is no need to poll App Store Connect afterwards.
