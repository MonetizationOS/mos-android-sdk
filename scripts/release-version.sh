#!/bin/sh
# Prints version=<x> for $GITHUB_OUTPUT when the Release workflow should release package.json's version: that version has
# no tag yet, and the run is manual or an earlier release is tagged. So the first release is always run by hand, and after
# it any run on main releases a version left untagged: the merged release PR, or a later push when that run was cancelled
# or failed (a failed upload makes no tag). Prints nothing otherwise.
# Usage: release-version.sh <event name>
set -e
cd "$(dirname "$0")/.."
version=$(sed -n 's/^ *"version": *"\([0-9][^"]*\)".*/\1/p' package.json | head -n 1)
[ -n "$version" ] || { echo "release-version: no version in package.json" >&2; exit 1; }
if git rev-parse -q --verify "refs/tags/v$version" > /dev/null; then exit 0; fi
if [ "$1" != workflow_dispatch ] && [ -z "$(git tag --list 'v*')" ]; then exit 0; fi
echo "version=$version"
