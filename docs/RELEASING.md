# Releasing

Work reaches `main` by pull request, like any other change. A release is
prepared on its own branch - cut from `main` when it is time to ship - and
reaches `main` by merge. So `main` is the latest release plus whatever has been
merged since, and the history says which release each piece of work shipped in.

```
                               v1.5.0          v1.5.1
main         ──●──●──●────────────●───●───────────●──
                      \          /     \         /
release/1.5            ●───●───●────────●───●───●
                       cut, prep       main in, prep
```

## Branches

| | |
|---|---|
| `main` | Where work lands, by pull request. Its version number is the last release that landed; anything merged since is not yet released. |
| `release/X.Y` | Where X.Y.0, X.Y.1 and every later patch to it are prepared. Cut from `main`, merged back for each release. |

Release branches are kept after the merge, not deleted. A patch to a shipped
version goes through that version's branch - `release/1.5` for a 1.5.1 - which
is the whole reason the branch outlives the merge.

## Versions

The version is declared once, in `app/build.gradle.kts`:

```kotlin
val versionMajor = 1
val versionMinor = 5
val versionPatch = 2
```

Everything else derives from it, including the version code, so the
name and the code cannot drift apart. Bump it as the **first commit on the
release branch**, never on `main` - `main` takes the new version through the
merge, which is what makes its version number mean "the last release that
landed". A build from `main` between releases therefore carries that release's
version and version code; it is not a version of its own.

Tags are `vX.Y.Z` and are cut on `main`, on the merge commit. A tag names a
version that shipped; a branch names a version being prepared.

## Cutting a version

A new major or minor version is cut from `main`, once what is on it is what
should ship:

```bash
./scripts/cut-release.sh 1.5     # or: make cut-release VERSION=1.5
```

That checks the tree is clean and `main` is level with origin, branches
`release/1.5`, bumps the version, commits and pushes.

A patch belongs on the branch of the version it follows. Land the fix on `main`
first, as a pull request like anything else, then:

```bash
./scripts/cut-release.sh 1.5.1   # checks out release/1.5, bumps, pushes
git merge main                   # on release/1.5: brings the fix across
```

The script does not bring `main` in, so that step is yours. Say in the merge
message that the fix arrived from `main`, so the history explains why a patch
branch merged its own trunk.

**A patch carries everything on `main`.** The merge brings whatever has landed
there since the branch was last merged, not only the fix. That is the right
answer while `main` holds nothing that is not meant to ship. When it does,
cherry-pick the fix's commits onto `release/X.Y` instead of merging `main`.

### Preparing the branch

Two more things go on the release branch before it lands:

- **Raise `highestPublishedVersionCode`** in `app/build.gradle.kts` to the
  version code of the release that most recently shipped - the one before this.
  It is the floor every later build has to clear, and nothing else remembers it.
  It has to be raised here and not on `main`: `main` still builds that release,
  so a floor equal to its own code would fail the check against the very version
  it guards. It was missed once, after 1.4.1, which is why it is written down.
- **The README's unit-test count**, if it changed.

Then `make ci` and `make verify`: the first is everything CI runs, in CI's
order, against your local signing key; the second prints the signing
certificate, which has to be the one every earlier release carries. Push, and CI
runs on the branch.

## Landing it

```bash
git checkout main
git merge --no-ff release/1.5
git tag -a v1.5.2 -m "PocketPrint 1.5.2"
git push origin main --follow-tags
```

`--no-ff` is deliberate: the merge commit is what records that a version
landed. Fast-forwarding would flatten the release into `main` and lose it.

CI runs on every pull request, on `main`, on every `release/**` push and on
every `v*` tag. Checking a release branch only when it merges means checking it
after the decision to ship has already been made.

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

The two can be mixed, and the useful mixture is the path and the alias in the
file with the passwords only in the environment, so no password sits on disk.
Whichever source names a value first wins, and a blank counts as unset — a file
with the passwords left empty falls through to the environment rather than
overriding it with nothing.

`keystore.properties` is gitignored, which also means `git clean -xfd` deletes
it: `-x` removes ignored files, which is exactly what an ignore list contains.
Keep the keystore itself outside the working tree for that reason. It lives in
`../signing-keys/` on the machine this was released from.

**A release build with no key fails rather than producing an unsigned APK.**
An unsigned release is not a weaker artifact, it is one no device will install,
and it lands next to the signed name with one word between them. CI is exempt,
because a pull request from a fork gets no secrets and should get none. To ask
for an unsigned release deliberately — to look at what R8 emitted, say:

```bash
./gradlew assembleRelease -PallowUnsignedRelease=true   # or: make release-unsigned
```

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
draft and presses the button. The draft's "What changed since vX.Y.Z" section is the
commit log since the previous tag — a first draft, not the notes. Edit it before publishing. `workflow_dispatch` does all of that except touch
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
