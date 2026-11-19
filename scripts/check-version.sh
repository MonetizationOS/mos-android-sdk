#!/bin/sh
# Fails unless package.json, Version.kt and gradle.properties agree and CHANGELOG.md has a section for that version.
# Pass the release tag (v0.9.0) to also require the tag to be that version.
set -e
cd "$(dirname "$0")/.."
kotlin=$(sed -n 's/^public const val MOS_VERSION: String = "\(.*\)"$/\1/p' mos-core/src/main/kotlin/com/monetizationos/sdk/Version.kt)
gradle=$(sed -n 's/^VERSION_NAME=//p' gradle.properties)
package=$(sed -n 's/^ *"version": *"\([0-9][^"]*\)".*/\1/p' package.json | head -n 1)
fail() { echo "check-version: $*" >&2; exit 1; }
[ -n "$kotlin" ] || fail "no MOS_VERSION in Version.kt"
[ "$gradle" = "$kotlin" ] || fail "Gradle VERSION_NAME $gradle != Kotlin MOS_VERSION $kotlin"
[ "$package" = "$kotlin" ] || fail "package.json version $package != Kotlin MOS_VERSION $kotlin"
grep -qx "## $kotlin" CHANGELOG.md || fail "CHANGELOG.md has no '## $kotlin' section"
[ -z "$1" ] || [ "$1" = "v$kotlin" ] || fail "tag $1 != v$kotlin"
echo "$kotlin"
