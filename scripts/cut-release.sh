#!/usr/bin/env bash
#
# Cut a version branch.
#
#   ./scripts/cut-release.sh 1.1      new version -> branches release/1.1 off main
#   ./scripts/cut-release.sh 1.1.1    patch       -> continues on release/1.1
#
# Bumps the single version declaration in app/build.gradle.kts, commits, and
# pushes. See docs/RELEASING.md.
set -euo pipefail

cd "$(dirname "$0")/.."

usage() { echo "usage: $0 <major.minor[.patch]>" >&2; exit 2; }

[ $# -eq 1 ] || usage
[[ $1 =~ ^([0-9]+)\.([0-9]+)(\.([0-9]+))?$ ]] || usage

major=${BASH_REMATCH[1]}
minor=${BASH_REMATCH[2]}
patch=${BASH_REMATCH[4]:-0}
version="$major.$minor.$patch"
branch="release/$major.$minor"

if [ -n "$(git status --porcelain)" ]; then
    echo "working tree is not clean; commit or stash first" >&2
    exit 1
fi

git fetch origin --quiet

if [ "$patch" = "0" ]; then
    # A new version starts from the latest, which is what main holds.
    [ "$(git rev-parse --abbrev-ref HEAD)" = "main" ] || {
        echo "cut a new version from main, not $(git rev-parse --abbrev-ref HEAD)" >&2
        exit 1
    }
    [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || {
        echo "main is not level with origin/main; pull or push first" >&2
        exit 1
    }
    git show-ref --verify --quiet "refs/heads/$branch" && {
        echo "$branch already exists" >&2
        exit 1
    }
    git checkout -b "$branch"
else
    # A patch belongs to the version it fixes, which is why release branches
    # outlive their merge.
    git show-ref --verify --quiet "refs/heads/$branch" ||
        git show-ref --verify --quiet "refs/remotes/origin/$branch" || {
            echo "$branch does not exist; $version has no version to patch" >&2
            exit 1
        }
    git checkout "$branch"
    git pull --ff-only --quiet
fi

perl -pi -e "s/^val versionMajor = .*/val versionMajor = $major/" app/build.gradle.kts
perl -pi -e "s/^val versionMinor = .*/val versionMinor = $minor/" app/build.gradle.kts
perl -pi -e "s/^val versionPatch = .*/val versionPatch = $patch/" app/build.gradle.kts

git add app/build.gradle.kts
git commit -q -m "Open $version"
git push -q -u origin "$branch"

echo "on $branch at $version — push with: git push"
