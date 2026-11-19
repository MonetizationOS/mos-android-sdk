# MOS Showcase

An example Compose app, one screen per SDK use case, plain placeholder content. Each screen is a single file with its MOS calls inline, so you can copy the pattern straight out of it. Every screen starts with a one-line explainer and ends with a **Last call** panel: the call made, `result.source`, and the raw response JSON. The iOS SDK has the same app in SwiftUI.

- `fixtures/`: the responses fixture mode answers from. Its README is the fixture server's spec.
- `app/`: Jetpack Compose, Material 3, minSdk 28. Screens in `app/src/main/kotlin/.../screens/`, shared plumbing in `.../support/`.

## Screens

| Section | Screen | Shows |
| --- | --- | --- |
| Basics | Gate content | `decide(resource)` on `article/1` (free) or `article/2` (premium), branch on `hasAccess(feature = "article", property = "enabled")` |
| | Metered access | Per-call `surface = "showcase-metered"`; each new `item/N` consumes the `items.views` meter, a repeat does not (`uniqueResources`); 3 free |
| | Feature switch | Boolean `ads.enabled` shows or hides an ad slot |
| | Surface properties | `decision.surfaceProperties["banner"]` (text, tone, cta) drawn natively, on surface `showcase-home` |
| Paywalls | Native paywall | `decision.experience("paywall")` is `Experience.Native`: copy and offer slots drawn in Compose, `redeem(offer.token)` on tap |
| | Web paywall | `decision.experience("webPaywall")` is `Experience.Web` in `MOSWebPaywallView` with a fixed-price `PriceProvider`; `onRedemption` and `onClose`. The view's `MOSWebPaywallRetainer` lives in a `ViewModel` so the page survives rotation and folding |
| | Redeem an offer | One offer per `RedemptionResult`: custom action, navigate, none, declined (and failed with Offline on), and what an app does with each |
| Identity | Sign in and out | `identify(userJwt)`, its `IdentifyResult`, and the next decision carrying `previousAnonymousIdentifier`; `identify(null)` to sign out |
| Offline | Offline | An Offline switch (a transport that fails every request): `source` moving network, cache, fallback, unavailable; `failOpen`; queued counter updates; `flush()`; `clearCache()` |
| Endpoints | App endpoints | `mos.app(namespace)`: a GET, a POST with a body, `Response` vs `Failed` |

**Home** lists the screens under three live rows: mode (Fixture, or Live and the host), the current identity, and where the last decision came from. **Settings** (gear icon): mode, public key, API base URL, endpoint namespace, and Reset identity (a new anonymous reader, cache and queue cleared). Saving a new mode, key or host rebuilds the MOS instance and resets the old one (identity, cache and queue). The SDK already keeps keys for different environments apart; the reset matters when the new key is for the same environment, as with a new host and the same key.

## Fixture mode

The default when no public key is configured. No key, no backend: a transport injected into `MOSConfig.transport` answers every request from `fixtures/` and keeps just enough state (meters per reader, signed in or not, claimed offers). The rest is the real SDK: identity, cache, metering queue, web paywall. Everything in the table above works in fixture mode.

## Live mode

Live mode talks to your own MonetizationOS org, set up as in the contract below. With a key prefilled as below, the app starts in Live mode; otherwise open Settings, choose Live, enter your org's `pk_` key and API base URL, and Save. To prefill them, put them in `local.properties` (gitignored) next to this README:

```
mos.publicKey=pk_<env>_<id>_<secret>
mos.apiBaseUrl=https://api.monetizationos.com
mos.endpointNamespace=showcase
```

On Sign in and out, paste a JWT from your org's JWT integration.

## Run

The app builds the SDK from the repository root (an included build), and packages `fixtures/` as its assets, so there is nothing to copy.

**Android Studio:** open `Examples/Showcase`. Studio writes the SDK path to `local.properties` here; the first sync copies it to the repository root's `local.properties`, which the SDK's build reads. Both files are gitignored.

**Command line:** the SDK's build needs the Android SDK path, from `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) or `sdk.dir` in either `local.properties`.

```sh
cd Examples/Showcase
export ANDROID_HOME=~/Library/Android/sdk    # or wherever your SDK is; JDK 17+
./gradlew :app:assembleDebug :app:testDebugUnitTest   # app, and the fixture server's unit tests
./gradlew :app:installDebug                            # onto a running emulator or device
# UI test: every screen in fixture mode, on a running emulator
./gradlew :app:connectedDebugAndroidTest
```

The UI test writes a screenshot per screen to the app's external files dir. `connectedDebugAndroidTest` uninstalls the app afterwards, so to keep them, run it through `am instrument` and pull:

```sh
./gradlew :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w -e class com.monetizationos.showcase.ShowcaseUiTest \
  com.monetizationos.showcase.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/com.monetizationos.showcase/files/shots /tmp/showcase-shots
```

The test launches with `--ez SHOWCASE_RESET true` (fixture mode and a new anonymous reader); pass the same extra to `adb shell am start -n com.monetizationos.showcase/.MainActivity` for a clean start.

## Live-org contract

What an org needs for every screen to work live; fixture mode needs none of it. The iOS Showcase uses the same names, so one org serves both.

**Surfaces.** `config.surface` is `showcase`; two screens pass another surface per call.

| Surface | Resource ids | Features | Surface properties |
| --- | --- | --- | --- |
| `showcase` | `article/1` (`meta.tier: free`), `article/2` (`meta.tier: premium`), `feed`, `paywall/native`, `paywall/web`, `offers/redeem` | `article.enabled` (boolean), `ads.enabled` (boolean) | `paywall`, `webPaywall`, `offers` |
| `showcase-metered` | `item/1` to `item/5`, `offline/1`, `offline/2` | `items.views` (meterable, unique resources, 3 per period) for `item/*`; `offline.views` (meterable, unique resources, a high limit) for `offline/*` | none |
| `showcase-home` | `home` | none | `banner` |

Every decision also carries `properties.platform` (`android`).

**Workflow behaviour** (the fixtures do the same):

- `article.enabled`: true for `tier: free`; for `tier: premium` and on `paywall/*`, true only for a subscriber.
- `ads.enabled`: false for a signed-in reader or a subscriber, true otherwise.
- Without access, `paywall/native` returns `paywall` and `paywall/web` returns `webPaywall`; with access, neither key.
- `banner`: `{ "text": String, "tone": "info" | "success" | "warning", "cta": String? }`, e.g. a sign-in prompt for anonymous readers and a welcome back for signed-in ones.
- For a signed-in reader, the `showcase` workflow links `context.properties.previousAnonymousIdentifier` to the account (`utils.identity.link`), so what the reader had before signing in follows them.

**Experiences.** One property key per experience slot.

| Key | Kind | Content |
| --- | --- | --- |
| `paywall` | app experience, `resolveAsJson` | `{ headline, body, benefits: [String] }` plus `mos-offer-slots` with the monthly and annual offers |
| `webPaywall` | html experience, `resolveAsWebContent` | Plan links `<a href="mos://redeem?token={{mos-offer-token}}">`, prices in `data-mos-product="{{storeProductId}}"` (and `data-mos-price="display-period"`), optional `<a href="mos://close">`. `fixtures/html/web-paywall.html` is a working example. |
| `offers` | app experience, `resolveAsJson` | `{ title }` plus `mos-offer-slots` with one offer per redemption outcome, in this order: monthly, navigate, free week, expired |

**Offers.** Presentation keys the app reads: `title`, `subtitle`, `badge` (optional), `storeProductId`, `fallbackPrice`.

| Offer | `storeProductId` | Redemption answers |
| --- | --- | --- |
| Monthly | `premium:monthly` | `custom:play-billing` with `properties` `{ productId: "premium", basePlanId: "monthly" }` |
| Annual (badge "Best value") | `premium:annual` | `custom:play-billing` with `properties` `{ productId: "premium", basePlanId: "annual" }` |
| Open a welcome page | none | `navigate` to a URL |
| Claim a free week | none | `none` the first time, declined after that |
| An expired offer | none | declined |

**Identity.** A JWT integration with claim key `sub`, the SDK's default `claimKey`. Paste one of its tokens on the Sign in screen.

**App endpoints.** An installed app in namespace `showcase` (Settings changes it) with `GET profile` and `POST notes` (any 2xx). An org without that app can use its own endpoints instead: create them in the default namespace and set the endpoint namespace to `default`.
