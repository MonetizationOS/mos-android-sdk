# Changesets

Every user-visible change needs a changeset: a small markdown file in this folder that says how to bump the version and what changed. [Changesets](https://github.com/changesets/changesets) turns them into `CHANGELOG.md` entries.

## Workflow in this repo

1. Run `pnpm changeset` and describe the change, or write the file by hand:

   ```md
   ---
   "mos-android-sdk": patch
   ---

   `decide` no longer ...
   ```

   Before 1.0 a breaking change is `minor`; `major` would release 1.0.0. So while the version is 0.x a public API break needs a `minor` changeset, along with the updated API dumps (`./gradlew :mos-core:updateKotlinAbi :mos-android:apiDump`).
2. Commit the file in `.changeset/` with the code change. A change that lands in both SDKs gets the same wording in the iOS SDK's changeset.
3. When merged to `main`, the **Release** workflow opens (or updates) a "chore: release mos-android-sdk" PR that bumps `package.json`, `Version.kt` and `gradle.properties`, updates `CHANGELOG.md`, and consumes the changeset files.
4. Merging that PR runs the tests, uploads both libraries to Maven Central, then tags the release (`v0.9.1`) and creates a GitHub release from the CHANGELOG section. Nothing is public on Maven Central until someone presses Publish on the deployment in the Central Portal.

A pull request that changes `mos-core/src/main` or `mos-android/src/main` fails the **Changeset** check without one. Label it `no-changelog` when it has nothing user-visible.
