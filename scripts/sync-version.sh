#!/bin/sh
# Copies the version `changeset version` wrote to package.json into Version.kt and gradle.properties.
set -e
cd "$(dirname "$0")/.."
version=$(sed -n 's/^ *"version": *"\([0-9][^"]*\)".*/\1/p' package.json | head -n 1)
[ -n "$version" ] || { echo "sync-version: no version in package.json" >&2; exit 1; }
kotlin=mos-core/src/main/kotlin/com/monetizationos/sdk/Version.kt
sed -i.bak "s/^public const val MOS_VERSION: String = \".*\"$/public const val MOS_VERSION: String = \"$version\"/" "$kotlin"
sed -i.bak "s/^VERSION_NAME=.*/VERSION_NAME=$version/" gradle.properties
rm "$kotlin.bak" gradle.properties.bak
echo "$version"
