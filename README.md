<div align="center">
  <a href="https://monetizationos.com">
  <img alt="MonetizationOS logo" src="https://app.monetizationos.com/static/monetizationos-logo.png" height="48">
  </a>
  <h1>MonetizationOS Android SDK</h1>
</div>

A MonetizationOS SDK for Kotlin, on Android and any JVM; Java callers are not supported. It runs in your app, makes a surface decision against the MOS API with a **public key**, and keeps working offline with a decision cache and deferred metering. The iOS SDK is [mos-ios-sdk](https://github.com/MonetizationOS/mos-ios-sdk) and the browser SDK is [mos-browser-sdk](https://github.com/MonetizationOS/mos-browser-sdk).

Version `0.9.0`, the first public release; breaking changes are allowed before 1.0. Changes per release are in [CHANGELOG.md](./CHANGELOG.md). Start with [`Examples/Showcase`](./Examples/Showcase), a Compose app with one screen per use case that runs on bundled fixtures with no account.

## Install

Maven Central. Your app needs:

- `minSdk` 28 or higher and `compileSdk` 36 or higher.
- Kotlin 2.3 or newer.
- For the inline `experience.decode<T>()` only: Java 17 `compileOptions` (`sourceCompatibility` and `targetCompatibility` `JavaVersion.VERSION_17`) and `jvmTarget` 17. `experience.decode(T.serializer())` works at any target.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.monetizationos:mos-android:0.9.0")
}
```

Two libraries: `mos-android` (Android: `MOS.create(context, config)`, the SharedPreferences identity store, on-device cache, queue and meter files, `MOSWebPaywallView`) and `mos-core` (pure Kotlin/JVM, which `mos-android` brings in). The library declares the `INTERNET` permission, so you don't need to.

On a plain JVM (17 or newer) depend on `com.monetizationos:mos-core:0.9.0` instead and create the client with `MOS.inMemory(config)`; the cache, queue and meter counts live in memory, and so does the anonymous identifier unless you pass an `identity.store`. Two limits of the JDK's `HttpURLConnection` apply there, not on Android: the body of a 401 or 407 response to a request that sent a body is lost (the status still arrives), and `EndpointMethod.Patch` fails with `RequestFailed`.

## Usage

```kotlin
val mos = MOS.create(context, MOSConfig(
    publicKey = "pk_live_…",
    surface = "app-paywall",
    app = AppInfo.from(context),
    identity = IdentityConfig(jwtProvider = { auth.currentToken() }),   // null only when signed out; the last token if a refresh fails
    onLog = consoleLogger,                                                // or your own logger
))

val result = mos.decide(Resource(id = "article/123", meta = mapOf("section" to "sport")))
if (result.hasAccess("article", "enabled")) { /* show the article */ }
val paywall = result.decision?.surfaceProperties?.get("article")       // open JSON from the workflow
when (result.source) {
    DecisionSource.Network -> Unit
    is DecisionSource.Cache, is DecisionSource.Fallback -> Unit          // served offline; metering is queued
    DecisionSource.Unavailable -> Unit                                   // no decision; hasAccess used failOpen
    else -> Unit                                                         // a source added in a later release
}

mos.identify(token)          // after sign-in and at every launch; pass null on sign-out
mos.flush()                  // on foreground or when connectivity returns
val redemption = mos.redeem(offer.token)
```

Create one `MOS` per process, for example in `Application.onCreate`, and share it; never one per Activity. Two instances with keys for the same environment share `cache.json`, `queue.json` and `meters.json` and overwrite each other's entries.

### Configuration

`MOSConfig(…)` takes these; all but the first three have defaults.

| Key | Default | What it does |
| --- | --- | --- |
| `publicKey` | | **Required.** `pk_*` key; safe to ship in the app. `sk_` keys are refused. |
| `surface` | | **Required.** Surface slug. `decide(…, surface = …)` overrides it per call. |
| `app` | | **Required.** App name and version, for the `User-Agent`. Each run of characters outside an HTTP token becomes `-`, and a blank name or version is sent as `app`. `AppInfo.from(context)` reads the app's label and version name. |
| `apiBaseUrl` | `https://api.monetizationos.com` | MOS API base URL. |
| `identity` | anonymous, SharedPreferences | `jwtProvider`, read fresh at each call; `store`, any `IdentityStore`, a key-value store for the anonymous identifier and the rest of the identity state; `claimKey`, the JWT claim that identifies a user (`sub`). See [Identity](#identity). |
| `offline` | 24 h TTL, fail-open | Cache `ttl`, `failOpen`, `fallbackToLatest`, `maxEntries` and `enforceMeters`. See [Offline cache and metering](#offline-cache-and-metering). |
| `requestTimeout` | 5 s | End to end per API call. After a timeout, decisions and counter updates use `min(requestTimeout, 2 s)` for ten minutes or until any response. Timeouts in those ten minutes don't extend them. Redemptions and endpoint calls always get the full timeout, and their own timeouts don't start the ten minutes. |
| `onLog` | discard | Structured events. See [Logging & observability](#logging--observability). |
| `transport` | `HttpURLConnection` | Bring your own `HttpTransport`, for example to mock it in tests. |

## Identity

Resolution order, highest precedence first:

1. an explicit `mos.identify(userJwt)`;
2. the `jwtProvider` function;
3. anonymous: the persisted anonymous identifier, else the SDK asks the server to mint one and persists it in private SharedPreferences.

A `jwtProvider` that returns null or throws signs the user out for that call and purges the decision cache, so if a token refresh fails, return the last token.

`identify()` is not persisted across launches. For a user who is still signed in, call it at every launch before any other call, or use `jwtProvider` instead; otherwise the first call runs anonymously, purges that user's cached decisions and can drop their queued counter updates.

The first decision after a sign-in carries `properties.previousAnonymousIdentifier` so a workflow can link the two. The SDK keeps sending it, across launches, until a decision that carried it comes back authenticated (`identity.isAuthenticated`); an expired token answered anonymously does not count.

Set `IdentityConfig(claimKey = …)` to the claim key of your JWT integration in MOS; it defaults to `"sub"`. The SDK keys a signed-in user's cache and queue by that claim's value, a string or a number, so a refreshed token for the same user keeps them. A token without it is keyed by the whole token, so every refresh looks like a new user: the cache is purged and queued counter updates can be dropped.

A decision for a JWT can come back anonymous, for an expired token or a server fallback. The SDK returns it (`identity.isAuthenticated` is false) but does not cache it or record its meter counts, so the signed-in user's entries stay theirs, and logs `cache:skipped`.

If the identity store does not keep a newly minted anonymous id (its `get(key)` does not return it), the SDK uses that id from memory until the process ends, rather than minting one per decision, and logs `identity:store-failed`.

## Resource and components

Each decision sends a `Resource(id, meta, extra)`. `extra` is a `JsonObject`, empty by default; its keys merge into the resource at the top level and are available in workflows as `resource.<key>`. Each decision also carries `properties.platform` (`android`; the iOS SDK sends `ios`) so one surface's workflow can pick per-store copy.

`decide(…, components = …)` runs only the named components of the surface: an empty list runs the surface workflow alone, and leaving it out runs them all.

```kotlin
val lesson = mos.decide(Resource(id = "lesson/42"), components = listOf("lesson-paywall"))
```

## Offline cache and metering

- Every decision is cached per `(surface, identity, resource, components)` for the TTL or until the earliest offer token in it expires, a web paywall's `mos://redeem` links and `data-mos-redeem` attributes included. On any failure the exact entry is served, else the latest for that surface, identity and components. `result.source` says which. `decide(…, policy = …)` picks `NetworkFirst` (the default), `CacheFirst(maxAge)`, `NetworkOnly` or `CacheOnly`.
- The cache never stores `identity.jwtClaims`, and its files are excluded from device backups.
- `MOS.create`'s default stores are per environment of the public key (`default` for a key that names none), so a staging and a production key in one install never share an anonymous id, cache, queue or meter counts. Files go in `noBackupFilesDir/mos-sdk/<environment>/`, and the default identity store, `AndroidIdentityStore(context, publicKey)`, uses the private SharedPreferences file `com.monetizationos.sdk.<environment>`.
- A decision served from the cache counts as a read: it queues `counter-updates` for the meters it consumed. `flush()` sends them, and a successful network decision flushes in the background when anything is queued.
- Meters keep counting offline. Each network decision records every meter's count. Until the next one, a meter in a decision served from the cache shows that count plus the reads counted on this device, so `remainingUnits` goes down as the reader reads. A unique-resource meter counts each resource once.
- `OfflineConfig(enforceMeters = true)` (off by default) turns a meter's access off once those reads use it up: the meter comes back with `hasAccess` false and `lockedOffline` true, and nothing is queued. A resource already counted this period stays open even then, which is more lenient than the server: online, a used-up meter is off for every resource. The cached decision is the one that granted access, so it has no paywall for this: show your own screen, or one cached from a surface of its own.
- Until the next network decision the device can't see a meter's period reset or reads on other devices, so offline the count can run out early or late.

```kotlin
// Online, now and then: cache the offline-limit screen from a surface of its own.
mos.decide(Resource(id = "offline-limit"), surface = "offline-limit")

// Offline, when a read comes back locked:
val meter = result.decision?.features?.get("article")?.properties?.get("enabled") as? Meterable
if (meter?.lockedOffline == true) {
    val limit = mos.decide(Resource(id = "offline-limit"), policy = CachePolicy.CacheOnly, surface = "offline-limit")
    // show limit's experience
}
```

## Paywalls

A workflow returns resolved experiences in its properties. `Experience` tells a native one (app JSON plus typed offers) from a web one (server-rendered HTML).

```kotlin
class PaywallViewModel : ViewModel() {
    val retainer = MOSWebPaywallRetainer()
    override fun onCleared() = retainer.release()      // destroys the retained WebView
}

when (val experience = result.decision?.experience("paywall")) {
    is Experience.Native -> { /* your Compose paywall: experience.content, experience.offers; mos.redeem(offer.token) on tap */ }
    is Experience.Web -> AndroidView(
        factory = { context ->
            MOSWebPaywallView(
                context = context,
                html = experience.html,
                mos = mos,
                priceProvider = myPrices,              // a PriceProvider backed by your Play Billing code
                onRedemption = ::handleRedemption,
                onClose = { dismiss() },
                retainer = paywallViewModel.retainer,
            )
        },
        onRelease = { it.destroy() },                  // detaches the view; the retained WebView lives on
    )
    null -> Unit
    else -> Unit                                       // a kind added in a later release
}

// The same for a native paywall's mos.redeem(offer.token).
fun handleRedemption(redemption: RedemptionResult) {
    when (redemption) {
        is RedemptionResult.Redeemed -> when (val action = redemption.action) {
            is Action.Custom -> startPurchase(action.type, action.properties)   // custom or custom:*, e.g. custom:play-billing: your Play Billing flow
            is Action.Navigate -> openUrl(action.url)                           // http(s) or your own app's scheme only, see below
            is Action.NoAction -> refreshDecision()                             // decide again: the redemption cleared the cache
            else -> refreshDecision()                                           // an action added in a later release
        }
        is RedemptionResult.Declined -> showMessage(redemption.message)         // the workflow refused it
        is RedemptionResult.Failed -> showRetry()                               // it may not have run; for a timeout or server error it may have, so check before retrying
    }
}
```

A redeemed offer clears the decision cache, as `clearCache()` does, so the next decision comes from the server. A `navigate` URL comes from your organization's redemption workflow: open it only if it is `http`, `https` or your own app's scheme.

Web paywall templates opt in with plain attributes: `data-mos-product="{{storeProductId}}"` on any element whose text should become the store's localized price (`data-mos-price="display-period"` for the period), and `<a href="mos://redeem?token={{mos-offer-token}}">` or `data-mos-redeem="{{mos-offer-token}}"` for the buy button. A second tap while a redemption is in flight is ignored, so `onRedemption` runs once per purchase attempt. An `http` or `https` link goes to `onOpenUrl`, which opens the system browser by default. If the WebView's renderer dies, the view logs `webpaywall:process-terminated` and calls `onClose` (it has no close control of its own).

A Play subscription is priced per base plan, so set an offer's `storeProductId` to `productId:basePlanId` (such as `premium:monthly`, RevenueCat's format) and have your `PriceProvider` look up that base plan. A one-time product is its product ID alone.

Pass a `MOSWebPaywallRetainer` kept in a `ViewModel` so rotation, fold and resize move the live page into the recreated Activity instead of reloading it.

## Endpoints

`callEndpoint` calls your organization's MOS endpoints with the public key; no user identity is sent. `app(namespace)` calls an installed app's endpoints; the namespace is install-time configuration, never a constant.

```kotlin
val order = mos.callEndpoint("orders/checkout", body = buildJsonObject { put("item", itemId) })
val user = mos.callEndpoint(listOf("users", userId), method = EndpointMethod.Get)   // each segment encoded whole

val orders = mos.app(installedNamespace).call("orders", method = EndpointMethod.Get, query = mapOf("page" to "1"))
if (orders is EndpointResult.Response && orders.response.isSuccess) { val page = orders.response.json }
```

## Failure model: fail-open

- Nothing throws after construction, except `CancellationException` when the calling coroutine is cancelled. A failed decision comes back as a `DecisionResult` whose `failure.reason` is a `FailureReason`: `RequestFailed`, `Timeout`, `InvalidJson`, `ApiError`, `HttpError`, `InvalidResponse`, `RateLimited`, `Unauthorized` or `NotConfigured`, served from the cache when it can be.
- Failure reasons, redemption actions, experience kinds, property types and decision sources can gain cases in a minor release, so give a `when` over one an `else ->` branch.
- `hasAccess` answers `offline.failOpen` when there is no decision.
- **No automatic retry** of `surface-decisions`: it consumes and has no idempotency key, so a retry risks double-consuming.
- Redirects are never followed: a 3xx comes back as the response, so the key, a JWT and a request body only ever go to the API host.

## Logging & observability

The SDK **never logs on its own.** `onLog` receives every `LogEvent` (`level`, `code`, `message`, `context`) with stable codes, for example `decision:success`, `decision:fallback`, `metering:queued` and `transport:degraded`. Pipe events to your own logger, or pass the bundled `consoleLogger` while debugging. Every request carries a deliberate `User-Agent` and an `X-MOS-Mobile-Version` header.

## Google Play data safety

The SDK's answers for the Play Console data-safety form:

| Data type | Why it is collected |
| --- | --- |
| Device or other IDs | The anonymous identifier the server mints and the SDK stores in private SharedPreferences. |
| User IDs | The subject of the host's JWT, sent when the host passes a token. |
| App interactions | The resource ids sent with decisions and deferred counter updates. |

- Purpose: App functionality only. Not used for tracking.
- Encrypted in transit (HTTPS to the MonetizationOS API).
- The SDK reads no advertising id, no Android ID and no location.

## Known limitations

- **Soft-gating, not enforcement.** Decisions and metering run in the app, so a modified client can skip them. Hard enforcement stays server-side.
- **No store purchase flow.** The SDK shows store prices through your `PriceProvider` and redeems offer tokens; the purchase itself is your Play Billing code.
- **Cache key.** `CacheFirst` and the exact cache entry match on surface, identity, resource id and components only. `properties`, `resource.meta` and `extra` are not part of the key, so a call that differs only in those gets the same entry.
- **Concurrent first decisions.** On a fresh install, decisions made before the first one returns each ask the server to mint an anonymous id. The SDK keeps the first id that comes back; meter counts made under the others do not follow the reader.
- **Counter updates can repeat.** A queued counter update whose request timed out stays queued and is sent again, so a meter without unique resources can count that read twice.
- **Linking is your workflow's job.** `previousAnonymousIdentifier` reaches the workflow in `context.properties`. The server does not link identities by itself; the workflow does, with `utils.identity.link`.
- **Anonymous history after sign-out.** After a sign-out the device goes back to its anonymous id, so the next account to sign in is offered the same anonymous history, even if an earlier account linked it. Call `reset()` after sign-out to start a new anonymous visitor instead.

## Contributing

Repository layout, tests, CI and releasing: see [CONTRIBUTING.md](./CONTRIBUTING.md). Report vulnerabilities privately, as described in [SECURITY.md](./SECURITY.md).

## License

MIT
