# Contributing

The iOS SDK ([MonetizationOS/mos-ios-sdk](https://github.com/MonetizationOS/mos-ios-sdk)) does the same things in Swift: a behaviour change lands in both, with tests in both. `fixtures/` holds conformance fixtures that both SDKs' tests load; its README lists what each file must produce. The web paywall's document and scripts (`mos-core/src/main/resources/com/monetizationos/sdk/webpaywall/`) are shared the same way. Change either and you change both repositories; `scripts/check-shared.sh` fails while the copies differ.

Open an issue before a large change or a new public API. Keep a pull request to one change, with tests, and CI green. Tests use clearly fake keys and ids (`pk_test_...`, `example.com`); never commit a real key, token or customer data.

## Repository layout

- `mos-core`: pure Kotlin/JVM, safe on Android API 28: decisions, identity, offline cache, metering, endpoints and the web paywall page.
- `mos-android`: `MOS.create(context, config)`, the SharedPreferences identity store, on-device cache, queue and meter files, and `MOSWebPaywallView`.
- `mos-core/api/`, `mos-android/api/`: the recorded public API of each library.
- `mos-core/src/main/resources/com/monetizationos/sdk/webpaywall/`: the web paywall's document and scripts, the same files the iOS SDK loads.
- `fixtures/`: conformance fixtures the tests load. The iOS SDK's tests load the same files.
- `Examples/Showcase`: an example Compose app, one screen per use case.

## Scripts

| Command | What it does |
| --- | --- |
| `scripts/kotlin-test.sh` | `mos-core` tests on the JVM (JDK 17 or newer). |
| `./gradlew :mos-core:test :mos-android:testDebugUnitTest :mos-android:assembleRelease` | All tests, including `mos-android` under Robolectric. Needs an Android SDK on `ANDROID_HOME`; without one `mos-android` is skipped, or the build fails when `CI` is set. |
| `./gradlew :mos-core:checkKotlinAbi :mos-android:apiCheck` | Fails when the public API differs from the dumps in `mos-core/api/` and `mos-android/api/`. |
| `./gradlew :mos-core:updateKotlinAbi :mos-android:apiDump` | Records an intended public API change in those dumps. Commit them with the change. |
| `scripts/check-version.sh [tag]` | Fails unless `package.json`, `Version.kt` and `gradle.properties` agree and `CHANGELOG.md` has a section for that version. |
| `scripts/check-changeset.sh [base]` | Fails unless a branch that changes `mos-core/src/main` or `mos-android/src/main` adds a changeset. |
| `scripts/check-shared.sh [path]` | Diffs `fixtures/` and the web paywall files against the iOS SDK (a clone of its `main`, or a local checkout at `path`). |

## Showcase

`Examples/Showcase` is a Compose app that runs on bundled fixtures with no account. Its README says how to build, run and UI-test it. A new SDK use case usually gets a screen here and in the iOS Showcase. CI builds it.

## Versioning & release

Every user-visible change needs a changeset: run `pnpm changeset` (after `pnpm install`), or write the file by hand; [`.changeset/README.md`](.changeset/README.md) shows the format. A change that lands in both SDKs gets the same wording in each. A pull request that changes `mos-core/src/main` or `mos-android/src/main` fails the Changeset check without one; label it `no-changelog` when nothing user-visible changed.

On `main`, the Release workflow opens or updates a "chore: release mos-android-sdk" pull request that bumps `package.json`, `Version.kt` and `gradle.properties` and writes `CHANGELOG.md` from the pending changesets, then runs CI on it. Merging it runs the tests, signs both libraries and uploads them to Maven Central, then makes the tag (`v0.9.1`) and a GitHub release from the CHANGELOG section. Nothing is public on Maven Central until you open the deployment in the Central Portal and press Publish, since a published version can never be replaced.

`scripts/release-version.sh` decides whether a run releases: the version in `package.json` must have no `v<version>` tag, and either the run is manual or an earlier `v*` tag exists. So the first release is always manual: run the Release workflow by hand on `main`. After that, any run on `main` releases an untagged version, whether or not changesets are pending. A failed upload or a cancelled run makes no tag, so the next push to `main` (or a manual run) retries it.

To redo a release after its tag exists, for example when the Central deployment was dropped before it was published, delete the GitHub release and its tag with `gh release delete v<version> --cleanup-tag`, then run the Release workflow by hand on `main`. A version already published on Central can't be uploaded again; release a patch instead.

The release workflow needs these repository secrets: `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` (a user token from the Central Portal, not the login), `SIGNING_KEY` (the output of `gpg --export-secret-keys --armor <key id>`) and `SIGNING_KEY_PASSWORD`. To get them: verify the namespace `com.monetizationos` in the Central Portal with a DNS TXT record, generate the user token there, and upload the public half of a GPG key to a keyserver such as keyserver.ubuntu.com.

## CI

CI runs on every pull request. The release pull request is opened with the workflow's `GITHUB_TOKEN`, which starts no workflows, so the Release workflow dispatches CI on its branch (`changeset-release/main`) instead. CI runs:

- `mos-core` tests on the JVM, `mos-android` unit tests under Robolectric, a release AAR build, the public API checks against the dumps (`checkKotlinAbi`, `apiCheck`) and a local Maven publish.
- The Showcase app builds and its fixture tests run.
- `scripts/check-version.sh`, and `scripts/check-shared.sh`, which checks that `fixtures/` and the web paywall files match the iOS SDK's copies.

The tests use fixtures and fake transports. The Showcase UI test is not part of CI, and nothing in CI uses a physical device, an Android emulator or a live MonetizationOS environment.
