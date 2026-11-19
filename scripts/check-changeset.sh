#!/bin/sh
# Fails unless a pull request that changes the libraries' sources adds a changeset. Pass the base branch (default main).
set -e
cd "$(dirname "$0")/.."
range="origin/${1:-main}...HEAD"
if ! git diff --name-only "$range" -- mos-core/src/main mos-android/src/main | grep -q .; then
    echo "check-changeset: no change under mos-core/src/main or mos-android/src/main"
    exit 0
fi
if git diff --name-only --diff-filter=A "$range" -- .changeset | grep -v '^.changeset/README.md$' | grep -q '\.md$'; then
    echo "check-changeset: changeset found"
    exit 0
fi
echo "::error::The library sources changed without a changeset. Run 'pnpm changeset' (see .changeset/README.md), or label the PR no-changelog."
exit 1
