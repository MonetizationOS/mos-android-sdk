#!/bin/sh
# Fails unless fixtures/ and the web paywall files match the iOS SDK's copies, which its tests and web paywall view
# load. Compares against a clone of MonetizationOS/mos-ios-sdk's main, or a local checkout given as $1.
# Needs SHARED_REPO_TOKEN while the iOS repository is private; without it the check is skipped with a warning.
set -e
cd "$(dirname "$0")/.."
ios="$1"
if [ -z "$ios" ]; then
  ios=$(mktemp -d)
  trap 'rm -rf "$ios"' EXIT
  # A private repository needs a token with read access in SHARED_REPO_TOKEN. Without one the check is
  # skipped with a warning rather than failed.
  url="https://${SHARED_REPO_TOKEN:+x-access-token:$SHARED_REPO_TOKEN@}github.com/MonetizationOS/mos-ios-sdk.git"
  if ! git clone --quiet --depth 1 "$url" "$ios" 2>/dev/null; then
    if [ -z "$SHARED_REPO_TOKEN" ]; then
      echo "::warning::check-shared skipped: cannot read MonetizationOS/mos-ios-sdk; set the SHARED_REPO_TOKEN secret"
      exit 0
    fi
    echo "check-shared: cannot clone MonetizationOS/mos-ios-sdk with SHARED_REPO_TOKEN" >&2
    exit 1
  fi
fi
status=0
diff -r fixtures "$ios/fixtures" || status=1
diff -r mos-core/src/main/resources/com/monetizationos/sdk/webpaywall "$ios/Sources/MonetizationOS/WebPaywall/shared" || status=1
[ $status -eq 0 ] && echo "check-shared: fixtures and web paywall match $ios" || echo "check-shared: copies differ from the iOS SDK" >&2
exit $status
