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

Everything else derives from it, including both flavours' version codes, so the
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

## Note on v1.0.0

The published `v1.0.0` pre-release points at an early commit and is left alone
as history. It predates the licence, the flavour split, and the printer
language detection that field testing corrected, so it is not the 1.0 anyone
should install. `v1.0.1` is that release, tagged on the code actually verified
against hardware.
