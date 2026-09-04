# Releasing

`main` always holds the latest version. Every version is built on its own
branch and reaches `main` by merge, so `main` is never a half-finished version
and the history says which work belonged to which release.

```
main       ──●────────────────────●───────────────────●──   latest version
              \                  /  \                /
release/1.1    ●──●──●──●──●──●──    ●──●──●──●──●──●        version 1.1 work
                                v1.1.0            v1.2.0
```

## Branches

| | |
|---|---|
| `main` | The latest version. Only ever advanced by merging a release branch. |
| `release/X.Y` | Where version X.Y is built. Cut from `main`, merged back when the version is done. |

Release branches are kept after the merge, not deleted. A patch to a shipped
version goes on that version's branch — `release/1.1` for a 1.1.1 — which is
the whole reason the branch outlives the merge.

## Versions

The version is declared once, in `app/build.gradle.kts`:

```kotlin
val versionMajor = 1
val versionMinor = 0
val versionPatch = 1
```

Everything else derives from it, including the version code, so the
name and the code cannot drift apart. Bump it as the **first commit on the
release branch**, never on `main` — `main` takes the new version through the
merge, which is what keeps "main is the latest version" true rather than
aspirational.

Tags are `vX.Y.Z` and are cut on `main`, on the merge commit. A tag names a
version that shipped; a branch names a version being built.

## Cutting a version

```bash
./scripts/cut-release.sh 1.1
```

That checks the tree is clean and `main` is current, branches `release/1.1`,
bumps the version, commits and pushes. Then do the work on that branch.

## Landing it

```bash
git checkout main
git merge --no-ff release/1.1
git tag -a v1.1.0 -m "PocketPrint 1.1.0"
git push origin main --follow-tags
```

`--no-ff` is deliberate: the merge commit is what records that a version
landed. Fast-forwarding would flatten the release into `main` and lose it.

CI runs on `main`, on every `release/**` push and on every `v*` tag. Checking a
release branch only when it merges means checking it after the decision to ship
has already been made.

## Signing

Releases are signed with a key that is not in this repository and never will
be. Until one is configured, `assembleRelease` produces
`app-release-unsigned.apk` and the release workflow refuses to start.
That is deliberate: the fallback is not the debug key. An APK signed with
Android's public debug keystore looks signed and is not — anyone at all can
build an update that installs over a user's copy and inherits the enabled print
service, which sees every document they print.

### The key, once

```bash
keytool -genkeypair -v -keystore pocketprint-release.jks \
  -alias pocketprint -keyalg RSA -keysize 4096 -validity 10000
```

Keep that file and its passwords somewhere they will still exist in ten years.
Losing the key is not recoverable: the package manager refuses an update signed
by a different key, so every existing install would have to be uninstalled and
reinstalled, taking its data with it.

### Signing locally

Either put `keystore.properties` at the repository root — gitignored, and the
keystore it points at is too:

```properties
storeFile=/absolute/path/to/pocketprint-release.jks
storePassword=...
keyAlias=pocketprint
keyPassword=...
```

...or set the same four as `POCKETPRINT_KEYSTORE`,
`POCKETPRINT_KEYSTORE_PASSWORD`, `POCKETPRINT_KEY_ALIAS` and
`POCKETPRINT_KEY_PASSWORD` in the environment.

### Signing on CI

Four repository secrets, the first of which is the keystore itself:

```bash
base64 -i pocketprint-release.jks | pbcopy   # -> POCKETPRINT_KEYSTORE_BASE64
```

plus `POCKETPRINT_KEYSTORE_PASSWORD`, `POCKETPRINT_KEY_ALIAS` and
`POCKETPRINT_KEY_PASSWORD`.

Pushing a `vX.Y.Z` tag then runs `.github/workflows/release.yml`: runs the
tests, builds and signs the APK, checks it declares no required hardware
feature and asks for exactly the permissions the docs describe, reads the
signing certificate's fingerprint back out of the signed APK — into the log and
into the release notes, so a download can be checked against it — and **drafts**
a release carrying the APK and its SHA-256 sum. It never publishes — someone reads the
draft and presses the button. `workflow_dispatch` does all of that except touch
the Releases page, which makes it a usable dry run.

If a release already exists for the tag — because a previous run failed part
way, or because somebody made one by hand — the builds are attached to it
instead, and its notes are left alone. Re-running a failed release is the first
thing anyone tries, so it has to work; and overwriting somebody's release notes
in order to re-attach the same three files would be a poor trade. Note that
attaching to a release that is already published makes those files public
immediately, and the run says so in its log.

### The first signed release installs alongside, not over

Everything published so far was a *debug* build, and debug builds carry
`applicationId com.gulshan.pocketprint.debug`. A release build is
`com.gulshan.pocketprint`, so to Android it is a different package: it installs
next to the old one rather than replacing it, and the old one keeps its saved
printers. Uninstall the `.debug` copy by hand, and turn the print service on
again for the new one.

## Note on v1.0.0

The published `v1.0.0` pre-release points at an early commit and is left alone
as history. It predates the licence, the flavour split that has since been
undone, and the printer
language detection that field testing corrected, so it is not the 1.0 anyone
should install. `v1.0.1` is that release, tagged on the code actually verified
against hardware.
