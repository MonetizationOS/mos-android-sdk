package com.monetizationos.sdk

import com.monetizationos.sdk.cache.CacheEntry
import com.monetizationos.sdk.cache.DecisionCache
import com.monetizationos.sdk.cache.DecisionCacheStore
import com.monetizationos.sdk.cache.FileDecisionCacheStore
import com.monetizationos.sdk.cache.InMemoryDecisionCacheStore
import com.monetizationos.sdk.cache.StorageDirectory
import com.monetizationos.sdk.cache.withoutClaims
import com.monetizationos.sdk.decision.Decision
import com.monetizationos.sdk.decision.earliestOfferExpiry
import com.monetizationos.sdk.decision.meterables
import com.monetizationos.sdk.decision.parseDecision
import com.monetizationos.sdk.identity.IdentityStoreKeys.ANONYMOUS_IDENTIFIER
import com.monetizationos.sdk.identity.IdentityStoreKeys.LAST_IDENTITY_KEY
import com.monetizationos.sdk.identity.IdentityStoreKeys.PENDING_PREVIOUS_ANONYMOUS_IDENTIFIER
import com.monetizationos.sdk.identity.InMemoryIdentityStore
import com.monetizationos.sdk.identity.ResolvedIdentity
import com.monetizationos.sdk.identity.anonIdentityKey
import com.monetizationos.sdk.identity.identityKind
import com.monetizationos.sdk.logging.Logger
import com.monetizationos.sdk.metering.FileMeterLedgerStore
import com.monetizationos.sdk.metering.FileMeteringQueueStore
import com.monetizationos.sdk.metering.InMemoryMeterLedgerStore
import com.monetizationos.sdk.metering.MeteringQueueStore
import com.monetizationos.sdk.metering.InMemoryMeteringQueueStore
import com.monetizationos.sdk.metering.MeterLedger
import com.monetizationos.sdk.metering.MeterLedgerEntry
import com.monetizationos.sdk.metering.MeterLedgerStore
import com.monetizationos.sdk.metering.MeteringEntry
import com.monetizationos.sdk.metering.MeteringQueue
import com.monetizationos.sdk.metering.periodStartMs
import com.monetizationos.sdk.transport.ApiClient
import com.monetizationos.sdk.transport.ApiOutcome
import com.monetizationos.sdk.transport.HttpOutcome
import com.monetizationos.sdk.transport.HttpUrlConnectionTransport
import com.monetizationos.sdk.transport.buildUserAgent
import com.monetizationos.sdk.transport.classify
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.time.Duration
import kotlin.time.TimeSource

internal const val SURFACE_DECISIONS_PATH = "/api/v1/surface-decisions"
internal const val COUNTER_UPDATES_PATH = "/api/v1/counter-updates"
internal const val OFFER_REDEMPTIONS_PATH = "/api/v1/offer-redemptions"

/**
 * The MonetizationOS client. Every call is a suspend function that never throws, except `CancellationException` when the
 * calling coroutine is cancelled: failures come back as values.
 * `surface-decisions` is never retried because it consumes meters. Construction throws `IllegalArgumentException`
 * for a key that is not `pk_`.
 */
public class MOS internal constructor(
    private val config: MOSConfig,
    device: DeviceInfo,
    private val clock: Clock,
    storageDirectory: (suspend () -> File)?,
    // The SDK's own cache, queue and meter ledger; tests inject others.
    cacheStore: DecisionCacheStore? = null,
    queueStore: MeteringQueueStore? = null,
    meterStore: MeterLedgerStore? = null,
) {
    /**
     * For mos-android: the device for the User-Agent and the directory for `cache.json`, `queue.json` and `meters.json`. The directory
     * is resolved once, on the IO dispatcher, before the first cache or queue access.
     * One instance per storage directory: two over the same files overwrite each other's entries.
     */
    @InternalMosApi
    public constructor(config: MOSConfig, device: DeviceInfo, storageDirectory: suspend () -> File) :
        this(config, device, Clock.System, storageDirectory)

    internal val logger = Logger(config.onLog)
    private val failOpen = config.offline.failOpen
    private val identityStore = config.identity.store ?: InMemoryIdentityStore()
    private val storage = storageDirectory?.let(::StorageDirectory)
    private val cache = DecisionCache(
        cacheStore ?: storage?.let { FileDecisionCacheStore { it.file("cache.json") } } ?: InMemoryDecisionCacheStore(),
        config.offline.maxEntries,
        logger,
    )
    private val queue = MeteringQueue(
        queueStore ?: storage?.let { FileMeteringQueueStore { it.file("queue.json") } } ?: InMemoryMeteringQueueStore(),
        logger,
    )
    private val ledger = MeterLedger(
        meterStore ?: storage?.let { FileMeterLedgerStore { it.file("meters.json") } } ?: InMemoryMeterLedgerStore(),
        logger,
    )
    internal val userAgent: String
    private val api: ApiClient

    // One lock for identity, cache and queue state; flushes are serialised separately so a slow
    // flush never holds up a decision.
    private val state = Mutex()
    private val flushLock = Mutex()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
    private val decisions = Coalescer<NetworkDecision>(background)
    private val redemptions = Coalescer<RedemptionResult>(background)
    private var explicitJwt: String? = null
    private var identityStateLoaded = false
    private var lastIdentityKey: String? = null
    private var pendingPreviousAnonymousIdentifier: String? = null

    // The link just dropped by jwt to another jwt: a request already carrying it still reports it once.
    private var droppedPreviousAnonymousIdentifier: String? = null

    // A minted id the identity store did not keep: used, when the store has none, until the process ends.
    @Volatile private var unpersistedAnonymousIdentifier: String? = null

    // Bumped by reset(): a response to a request sent before it is never applied, and never shared.
    private var generation = 0L

    // Bumped by clearCache(): a response to a request sent before it is returned but never cached, and never shared.
    private var cacheGeneration = 0L

    /** The latest automatic flush, so tests can wait for it. */
    @Volatile internal var backgroundFlush: Job? = null

    init {
        if (!config.publicKey.startsWith("pk_")) {
            logger.error("config:invalid-key", "Refusing a key that is not a public pk_ key.")
            throw IllegalArgumentException("MonetizationOS needs a public pk_ key; a secret key must never ship in an app.")
        }
        userAgent = buildUserAgent(config.app, device)
        val transport = config.transport ?: HttpUrlConnectionTransport()
        api = ApiClient(config.apiBaseUrl, config.publicKey, userAgent, transport, config.requestTimeout, clock, logger)
    }

    /**
     * Explicit identity, highest precedence. Null clears it and falls back to the provider or the anon id.
     * Resolves identity now and applies the identity-change rules (cache purge, pending link); no network call.
     * Kept in memory only: for a signed-in user, call it at every launch before any other call, or use `jwtProvider`.
     */
    public suspend fun identify(userJwt: String?): IdentifyResult {
        state.withLock { explicitJwt = userJwt?.takeIf { it.isNotBlank() } }
        return resolveInLock { identity ->
            loadIdentityState()
            val previous = lastIdentityKey
            val changed = noteIdentity(identity.key)
            IdentifyResult(changed, identityKindOf(previous), identityKindOf(identity.key), pendingPreviousAnonymousIdentifier != null)
        }.second
    }

    /** The persisted anonymous identifier, if any. */
    public suspend fun anonymousIdentifier(): String? = readAnonymousIdentifier()

    /**
     * [surface] overrides `config.surface` for this call. [components] names the surface's components to run: null runs them all,
     * empty runs only the surface workflow, unknown slugs are ignored. Each selection has its own cache entries and fallback;
     * order and duplicates do not matter.
     */
    public suspend fun decide(
        resource: Resource,
        properties: JsonObject? = null,
        policy: CachePolicy = CachePolicy.NetworkFirst,
        surface: String? = null,
        components: List<String>? = null,
    ): DecisionResult = try {
        decideInternal(resource, properties, policy, surface ?: config.surface, components?.distinct()?.sorted())
    } catch (e: Exception) {
        rethrowIfCancelled(e)
        val failure = unexpected(e)
        logger.error("decision:error", "Decision failed unexpectedly.", "reason" to failure.reason.value)
        DecisionResult(null, DecisionSource.Unavailable, failure, failOpen)
    }

    /**
     * Redeems an offer's token. A [RedemptionResult.Redeemed] clears the decision cache, as [clearCache] does. Uses the
     * full `requestTimeout` even in fail-fast, and its own timeout never enters fail-fast.
     */
    public suspend fun redeem(offerToken: String, properties: JsonObject? = null): RedemptionResult = try {
        redeemInternal(offerToken, properties)
    } catch (e: Exception) {
        rethrowIfCancelled(e)
        redeemFailed(unexpected(e))
    }

    /** Sends queued counter updates for the current identity. Call on app foreground or when connectivity returns. */
    public suspend fun flush(): FlushResult = try {
        flushQueue()
    } catch (e: Exception) {
        rethrowIfCancelled(e)
        FlushResult(0, 0, state.withLock { queue.countFor(lastIdentityKey) }, unexpected(e))
    }

    /**
     * Plain HTTP to an org endpoint (`namespace = null`) or an app's, with `X-MOS-Key`; any status is a [EndpointResult.Response].
     * [timeout] defaults to `requestTimeout`, even in fail-fast, and its own timeout never enters fail-fast.
     */
    public suspend fun callEndpoint(
        path: String,
        method: EndpointMethod = EndpointMethod.Post,
        body: JsonElement? = null,
        query: Map<String, String>? = null,
        headers: Map<String, String>? = null,
        namespace: String? = null,
        timeout: Duration? = null,
    ): EndpointResult {
        val segments = path.removePrefix("/").split('/')
        return callEndpoint(EndpointCall(segments.joinToString("/") { percentEncode(it) }, segments, method, namespace), body, query, headers, timeout)
    }

    /** [callEndpoint] with the path as segments, each percent-encoded whole: `"a/b"` stays one segment, `a%2Fb`. */
    public suspend fun callEndpoint(
        pathSegments: List<String>,
        method: EndpointMethod = EndpointMethod.Post,
        body: JsonElement? = null,
        query: Map<String, String>? = null,
        headers: Map<String, String>? = null,
        namespace: String? = null,
        timeout: Duration? = null,
    ): EndpointResult {
        val call = EndpointCall(pathSegments.joinToString("/") { percentEncode(it) }, pathSegments, method, namespace)
        return callEndpoint(call, body, query, headers, timeout)
    }

    private suspend fun callEndpoint(
        call: EndpointCall,
        body: JsonElement?,
        query: Map<String, String>?,
        headers: Map<String, String>?,
        timeout: Duration?,
    ): EndpointResult = try {
        callEndpointInternal(call, body, query, headers, timeout)
    } catch (e: Exception) {
        rethrowIfCancelled(e)
        endpointFailed(call, unexpected(e))
    }

    /** The endpoints of the app installed under [namespace], which is install configuration, never a constant. */
    public fun app(namespace: String): AppClient = AppClient(this, namespace)

    /** Drops cached decisions for every identity, e.g. after a purchase changed entitlements. A redeemed offer does this itself. */
    public suspend fun clearCache() {
        state.withLock {
            cacheGeneration++
            cache.clear("entitlements-changed")
        }
    }

    /**
     * Forgets the explicit JWT, the anonymous id, the last identity key, the pending link, the decision cache, the metering queue
     * and the offline meter counts.
     */
    public suspend fun reset() {
        state.withLock {
            // Not cancellable: a reset is all or nothing.
            withContext(NonCancellable) {
                generation++
                explicitJwt = null
                unpersistedAnonymousIdentifier = null
                writeStore(ANONYMOUS_IDENTIFIER, null)
                writeStore(LAST_IDENTITY_KEY, null)
                writeStore(PENDING_PREVIOUS_ANONYMOUS_IDENTIFIER, null)
                cache.clear("reset")
                queue.clear()
                ledger.clear()
                pendingPreviousAnonymousIdentifier = null
                droppedPreviousAnonymousIdentifier = null
                lastIdentityKey = null
                identityStateLoaded = true
            }
        }
    }

    private suspend fun decideInternal(
        resource: Resource,
        properties: JsonObject?,
        policy: CachePolicy,
        surface: String,
        components: List<String>?,
    ): DecisionResult {
        if (surface.isEmpty()) {
            val failure = Failure(FailureReason.RequestFailed, message = "The surface is empty.")
            logger.warn("decision:error", "Decision refused: the surface is empty.", "reason" to failure.reason.value, "surface" to surface)
            return DecisionResult(null, DecisionSource.Unavailable, failure, failOpen)
        }
        val (identity, sent) = resolveInLock { identity ->
            noteIdentity(identity.key)
            Triple(pendingPreviousAnonymousIdentifier, generation, cacheGeneration)
        }
        val (pending, sentGeneration, sentCacheGeneration) = sent
        when (policy) {
            CachePolicy.CacheOnly -> return serveCached(surface, components, identity, resource, failure = null)
            is CachePolicy.CacheFirst -> serveFresh(surface, components, identity, resource, policy.maxAge)?.let { return it }
            CachePolicy.NetworkFirst, CachePolicy.NetworkOnly -> Unit
        }

        // `platform` lets one surface's workflow pick per-store copy; a host value wins.
        val requestProperties = buildMap {
            put("platform", JsonPrimitive("android"))
            properties?.let(::putAll)
            pending?.let { put("previousAnonymousIdentifier", JsonPrimitive(it)) }
        }
        val body = buildJsonObject {
            put("surfaceSlug", surface)
            put("identity", identity.requestJson(mintFallback = true))
            put("resource", resource.toJson())
            put("properties", JsonObject(requestProperties))
            components?.let { put("components", JsonArray(it.map(::JsonPrimitive))) }
        }
        var joined = false
        val network = decisions.run(
            "$sentGeneration:$sentCacheGeneration:${canonicalJson(body)}",
            onJoin = {
                joined = true
                logger.debug("decision:coalesced", "Awaiting an identical in-flight decision request.", "surface" to surface, "resourceId" to resource.id)
            },
        ) { requestDecision(surface, components, identity, resource, body, pending, sentGeneration, sentCacheGeneration) }

        val decision = network.decision
        if (decision == null) {
            val failure = network.failure ?: Failure(FailureReason.InvalidResponse, message = "Response is not a surface decision.")
            return if (policy == CachePolicy.NetworkOnly) {
                unavailable(surface, resource, failure)
            } else {
                serveCached(surface, components, identity, resource, failure)
            }
        }
        // The link is accepted once: only the caller whose request carried it is told.
        return DecisionResult(decision, DecisionSource.Network, null, failOpen, network.linkedAnonymousIdentifier && !joined)
    }

    /** The one `surface-decisions` call, shared by coalesced callers. Applies the response unless it is stale. */
    private suspend fun requestDecision(
        surface: String,
        components: List<String>?,
        identity: ResolvedIdentity,
        resource: Resource,
        body: JsonObject,
        sentPending: String?,
        sentGeneration: Long,
        sentCacheGeneration: Long,
    ): NetworkDecision {
        logger.debug(
            "decision:start", "Requesting a surface decision.",
            "surface" to surface, "resourceId" to resource.id, "identity" to identityKind(identity.key),
        )
        val started = TimeSource.Monotonic.markNow()
        val outcome = api.post(SURFACE_DECISIONS_PATH, body)
        val latencyMs = started.elapsedNow().inWholeMilliseconds
        val decision = (outcome as? ApiOutcome.Ok)?.let { (it.body as? JsonObject)?.let(::parseDecision) }
        if (decision == null) {
            val failure = when (outcome) {
                is ApiOutcome.Failed -> outcome.failure
                is ApiOutcome.Ok -> Failure(FailureReason.InvalidResponse, outcome.status, message = "Response is not a surface decision.")
            }
            logger.warn(
                "decision:error", "Decision request failed.",
                "surface" to surface, "reason" to failure.reason.value, "status" to failure.status, "statusCode" to failure.statusCode, "latencyMs" to latencyMs,
            )
            return NetworkDecision(null, failure, linkedAnonymousIdentifier = false)
        }

        val linked = onDecisionSuccess(surface, components, identity, resource, decision, sentPending, sentGeneration, sentCacheGeneration)
        logger.info(
            "decision:success", "Decision received.",
            "surface" to surface, "latencyMs" to latencyMs, "eventId" to decision.eventId, "source" to "network",
        )
        return NetworkDecision(decision, null, linked)
    }

    private suspend fun onDecisionSuccess(
        surface: String,
        components: List<String>?,
        identity: ResolvedIdentity,
        resource: Resource,
        decision: Decision,
        sentPending: String?,
        sentGeneration: Long,
        sentCacheGeneration: Long,
    ): Boolean {
        val now = clock.nowMs()
        var linked = false
        // Not cancellable: the link, the cache entry and the meter counts land together or not at all.
        val flushNeeded = state.withLock {
            withContext(NonCancellable) {
                // Only an authenticated answer ran as the user, so only it can link; one answered anonymously (an expired
                // token) leaves the link pending for the next decision. Once delivered, only a reset stops it counting.
                if (sentPending != null && decision.identity.isAuthenticated && sentGeneration == generation &&
                    (pendingPreviousAnonymousIdentifier == sentPending || droppedPreviousAnonymousIdentifier == sentPending)
                ) {
                    setPending(null)
                    linked = true
                    logger.info("identity:previous-anon-sent", "Previous anonymous identifier delivered on an authenticated decision.")
                }
                val stale = staleReason(identity, sentGeneration)
                if (stale != null) {
                    logger.debug("identity:stale-response", "Response no longer matches the current identity; not applied.", "reason" to stale)
                    return@withContext false
                }
                val anonymousId = persistAnonymousIdentifier(decision, identity)
                // A minted identity is only known once the response names it.
                val cacheKey = identity.key ?: anonymousId?.let(::anonIdentityKey)
                if (identity.key == null) {
                    setLastIdentityKey(cacheKey)
                    dropSelfLink(cacheKey)
                }
                if (identity is ResolvedIdentity.Jwt && !decision.identity.isAuthenticated) {
                    // An expired token, answered as a throwaway anonymous identity: not the user's entry or meter counts.
                    logger.debug(
                        "cache:skipped", "Decision answered anonymously for a JWT; not cached.",
                        "reason" to "anonymous-answer", "surface" to surface,
                    )
                } else if (cacheKey != null && sentCacheGeneration == cacheGeneration) {
                    // Sent before clearCache(): it may predate the entitlement change.
                    val ttlExpiry = saturatingAdd(now, config.offline.ttl)
                    val expiresAt = earliestOfferExpiry(decision)?.let { minOf(it, ttlExpiry) } ?: ttlExpiry
                    cache.put(CacheEntry(surface, cacheKey, resource.id, withoutClaims(decision.raw), now, expiresAt, components))
                    recordMeters(decision, cacheKey, resource.id, now)
                }
                identity.key != null && queue.size() > 0
            }
        }
        if (flushNeeded) backgroundFlush = background.launch { flushQueue() }
        return linked
    }

    /** Call under [state]. Null when a response may still be applied. */
    private suspend fun staleReason(identity: ResolvedIdentity, sentGeneration: Long): String? = when {
        sentGeneration != generation -> "reset"
        // Another mint landed first; persisting this one would orphan the first id's cache and meters.
        identity == ResolvedIdentity.Mint && readAnonymousIdentifier() != null -> "anonymous-already-stored"
        lastIdentityKey != identity.key -> "identity-changed"
        else -> null
    }

    private suspend fun persistAnonymousIdentifier(decision: Decision, identity: ResolvedIdentity): String? {
        val id = decision.identity.identifier?.takeIf { it.isNotBlank() && !decision.identity.isAuthenticated } ?: return null
        val stored = readAnonymousIdentifier()
        // A JWT answered anonymously gets a fresh id each time; it must not replace the device's own.
        val keepStored = identity is ResolvedIdentity.Jwt && stored != null
        if (stored != id && !keepStored) {
            // Read back: a store can drop a write without throwing.
            if (writeStore(ANONYMOUS_IDENTIFIER, id) && readStore(ANONYMOUS_IDENTIFIER) == id) {
                unpersistedAnonymousIdentifier = null
                logger.debug("identity:anon-persisted", "Anonymous identifier persisted.", "anonymousIdentifier" to id)
            } else {
                unpersistedAnonymousIdentifier = id
                logger.warn(
                    "identity:store-failed", "The identity store did not keep the anonymous identifier; using it from memory.",
                    "store" to "identity",
                )
            }
        }
        return id
    }

    /** `CacheFirst`: the exact entry when it is young enough, without touching the network. */
    private suspend fun serveFresh(
        surface: String,
        components: List<String>?,
        identity: ResolvedIdentity,
        resource: Resource,
        maxAge: Duration,
    ): DecisionResult? {
        val key = identity.key ?: return null
        val now = clock.nowMs()
        val entry = state.withLock { cache.exact(surface, key, resource.id, components, now) } ?: return null
        if (now - entry.storedAt > maxAge.inWholeMilliseconds) return null
        return served(surface, identity, resource, entry, DecisionSource.Cache(now - entry.storedAt), failure = null)
    }

    /** Exact entry, else the latest for this surface and identity, else unavailable. */
    private suspend fun serveCached(
        surface: String,
        components: List<String>?,
        identity: ResolvedIdentity,
        resource: Resource,
        failure: Failure?,
    ): DecisionResult {
        val key = identity.key
        if (key != null) {
            val now = clock.nowMs()
            val hit: Pair<CacheEntry, DecisionSource>? = state.withLock {
                val exact = cache.exact(surface, key, resource.id, components, now)
                when {
                    exact != null -> exact to DecisionSource.Cache(now - exact.storedAt)
                    config.offline.fallbackToLatest ->
                        cache.latest(surface, key, components, now)?.let { it to DecisionSource.Fallback(now - it.storedAt, it.resourceId) }
                    else -> null
                }
            }
            if (hit != null) served(surface, identity, resource, hit.first, hit.second, failure)?.let { return it }
        }
        return unavailable(surface, resource, failure)
    }

    /** A cached serve still counts; see [applyOfflineMeters]. */
    private suspend fun served(
        surface: String,
        identity: ResolvedIdentity,
        resource: Resource,
        entry: CacheEntry,
        source: DecisionSource,
        failure: Failure?,
    ): DecisionResult? {
        val cached = parseDecision(entry.decision) ?: return null
        val anonymousIdentifier = (identity as? ResolvedIdentity.Anonymous)?.id
        // Not cancellable: the queued update and the ledger count land together.
        val decision = state.withLock {
            withContext(NonCancellable) { applyOfflineMeters(cached, entry.identityKey, anonymousIdentifier, resource.id, clock.nowMs()) }
        }
        when (source) {
            is DecisionSource.Cache ->
                logger.info("decision:cache-hit", "Serving a cached decision.", "surface" to surface, "ageMs" to source.ageMs, "resourceId" to resource.id)
            is DecisionSource.Fallback ->
                logger.info(
                    "decision:fallback", "Serving the latest cached decision for this surface.",
                    "surface" to surface, "ageMs" to source.ageMs, "fromResourceId" to source.fromResourceId,
                )
            else -> Unit
        }
        return DecisionResult(decision, source, failure, failOpen)
    }

    /**
     * Call under [state]. A network decision resets each of its meters to the server's count, plus the updates still queued,
     * which the server has not counted.
     */
    private suspend fun recordMeters(decision: Decision, identityKey: String, resourceId: String, now: Long) {
        var changed = ledger.prune(takenBefore = saturatingSubtract(now, config.offline.ttl))
        val queued = queue.entriesFor(identityKey)
        for ((_, _, meter) in meterables(decision)) {
            val consumed = meter.consumedUnits ?: continue
            val total = meter.totalUnits ?: continue
            val unique = meter.uniqueResources ?: false
            val countedHere = meter.consumedInRequest == true || meter.resourceIdUsed == true
            val periodStart = periodStartMs(meter.periodStart)
            val resources = ledger.entry(identityKey, meter.counterId)?.resources.orEmpty()
                .filter { periodStart == null || it.at >= periodStart }
            var entry = MeterLedgerEntry(identityKey, meter.counterId, consumed, total, unique, takenAt = now, localUnits = 0.0, resources = resources)
            if (countedHere) entry = entry.withResource(resourceId, now)
            val pending = queued.filter { it.counterId == meter.counterId && !(unique && countedHere && it.resourceId == resourceId) }
            ledger.put(entry.copy(localUnits = pending.sumOf { it.update }))
            changed = true
        }
        if (changed) ledger.save()
    }

    /**
     * Call under [state]. Serving from the cache stands in for a consuming request: each meter the cached decision consumed is
     * queued for the server and counted on the device. A meter the ledger knows also shows that count, and with
     * `offline.enforceMeters` loses access once it is used up.
     */
    private suspend fun applyOfflineMeters(
        decision: Decision,
        identityKey: String,
        anonymousIdentifier: String?,
        resourceId: String,
        now: Long,
    ): Decision {
        var raw = decision.raw
        var ledgerChanged = false
        for ((featureSlug, propertySlug, meter) in meterables(decision)) {
            // Only a meter with a count: an `on` or `off` meter carries none and stays as the server set it.
            val entry = if (meter.totalUnits == null) null else ledger.entry(identityKey, meter.counterId)
            // A resource already counted this period stays open, as it was paid for.
            val seen = entry?.counted(resourceId) ?: false
            val locked = config.offline.enforceMeters && meter.hasAccess && !seen && entry != null && entry.consumedUnits >= entry.totalUnits
            var counted = 0.0
            if (meter.consumedInRequest == true && meter.hasAccess && !locked && !seen) {
                val units = if (entry?.uniqueResources == true) 1.0 else meter.consumedUnitsInRequest ?: 1.0
                if (queue.enqueue(MeteringEntry(identityKey, anonymousIdentifier, meter.counterId, resourceId, units, now))) counted = units
            }
            if (entry == null) continue
            val historic = entry.consumedUnits
            if (counted > 0) {
                ledger.put(entry.copy(localUnits = entry.localUnits + counted).withResource(resourceId, now))
                ledgerChanged = true
            }
            if (locked) {
                logger.info(
                    "metering:locked", "Reads counted on this device used up the meter; access is off until a network decision.",
                    "counterId" to meter.counterId, "resourceId" to resourceId, "consumedUnits" to historic, "totalUnits" to entry.totalUnits,
                )
            }
            val property = (raw.obj("features")?.obj(featureSlug)?.obj("properties")?.obj(propertySlug) ?: EmptyJsonObject).toMutableMap()
            property["hasAccess"] = JsonPrimitive(meter.hasAccess && !locked)
            property["consumedUnits"] = jsonNumber(historic + counted)
            property["remainingUnits"] = jsonNumber(maxOf(0.0, entry.totalUnits - historic - counted))
            property["totalUnits"] = jsonNumber(entry.totalUnits)
            property["uniqueResources"] = JsonPrimitive(entry.uniqueResources)
            property["consumedInRequest"] = JsonPrimitive(counted > 0)
            property["consumedUnitsInRequest"] = jsonNumber(counted)
            if (entry.uniqueResources) property["resourceIdUsed"] = JsonPrimitive(seen)
            if (locked) property["lockedOffline"] = JsonPrimitive(true)
            raw = raw.setting(JsonObject(property), "features", featureSlug, "properties", propertySlug)
        }
        if (ledgerChanged) ledger.save()
        return if (raw == decision.raw) decision else parseDecision(raw) ?: decision
    }

    private fun unavailable(surface: String, resource: Resource, failure: Failure?): DecisionResult {
        logger.warn("decision:unavailable", "No decision available.", "surface" to surface, "resourceId" to resource.id, "failOpen" to failOpen)
        return DecisionResult(null, DecisionSource.Unavailable, failure, failOpen)
    }

    /** The identity is resolved once the flush holds [flushLock]: one queued behind another must not prune as a user who has since left. */
    private suspend fun flushQueue(): FlushResult = flushLock.withLock {
        val (identity, _) = resolveInLock { noteIdentity(it.key) }
        val key = identity.key
        var dropped = state.withLock { queue.prune(clock.nowMs(), key) }
        var sent = 0
        var failure: Failure? = null
        while (key != null && failure == null) {
            val entry = state.withLock { queue.oldestFor(key) } ?: break
            val sendAs = entry.anonymousIdentifier?.let { ResolvedIdentity.Anonymous(it) } ?: identity
            val body = buildJsonObject {
                put("identity", sendAs.requestJson(mintFallback = false))
                put("counterId", entry.counterId)
                put("update", jsonNumber(entry.update))
                put("resourceId", entry.resourceId)
            }
            when (val outcome = api.post(COUNTER_UPDATES_PATH, body)) {
                is ApiOutcome.Ok ->
                    if ((outcome.body as? JsonObject)?.string("status") == "success") {
                        state.withLock { queue.remove(entry) }
                        sent++
                    } else {
                        // A 2xx that is not a success never succeeds on retry, so it must not block the queue head.
                        state.withLock { queue.remove(entry) }
                        dropped++
                        logger.warn(
                            "metering:rejected", "Counter update rejected; dropped.",
                            "counterId" to entry.counterId, "resourceId" to entry.resourceId,
                            "status" to outcome.status, "statusCode" to JsonNull,
                        )
                    }
                is ApiOutcome.Failed ->
                    if (isRejection(outcome.failure)) {
                        state.withLock { queue.remove(entry) }
                        dropped++
                        logger.warn(
                            "metering:rejected", "Counter update rejected; dropped.",
                            "counterId" to entry.counterId, "resourceId" to entry.resourceId,
                            "status" to JsonPrimitive(outcome.failure.status), "statusCode" to JsonPrimitive(outcome.failure.statusCode),
                        )
                    } else {
                        failure = outcome.failure
                    }
            }
        }
        // Only this identity's entries can be sent now, so only they count as remaining.
        val remaining = state.withLock { queue.countFor(key) }
        val stoppedBy = failure
        if (stoppedBy != null) {
            logger.warn(
                "metering:flush-failed", "Flush stopped; remaining updates kept.",
                "reason" to stoppedBy.reason.value, "status" to JsonPrimitive(stoppedBy.status), "sent" to sent, "remaining" to remaining,
            )
        } else if (sent > 0 || dropped > 0) {
            logger.info("metering:flushed", "Queued counter updates flushed.", "sent" to sent, "remaining" to remaining, "dropped" to dropped)
        }
        FlushResult(sent, dropped, remaining, stoppedBy)
    }

    private suspend fun redeemInternal(offerToken: String, properties: JsonObject?): RedemptionResult {
        val (identity, sentGeneration) = resolveInLock { identity ->
            noteIdentity(identity.key)
            generation
        }
        if (identity == ResolvedIdentity.Mint) {
            return redeemFailed(Failure(FailureReason.NotConfigured, message = "No anonymous identifier or JWT yet; make a decision first."))
        }
        val body = buildJsonObject {
            put("identity", identity.requestJson(mintFallback = false))
            put("offerToken", offerToken)
            properties?.let { put("properties", it) }
        }
        // A double-tap on a buy button redeems once. It may outlast fail-fast's 2s, so it gets the full timeout.
        return redemptions.run(
            "$sentGeneration:${canonicalJson(body)}",
            onJoin = { logger.debug("redeem:coalesced", "Awaiting an identical in-flight redemption.") },
        ) { redemptionResult(api.postRaw(OFFER_REDEMPTIONS_PATH, body, failFast = false)) }
    }

    /** Once per redemption, however many callers share it. */
    private suspend fun redemptionResult(outcome: HttpOutcome): RedemptionResult {
        if (outcome is HttpOutcome.Failed) return redeemFailed(outcome.failure)
        val http = (outcome as HttpOutcome.Response).response
        // The server sends a decline with the body's statusCode as the HTTP status, so read it before classifying.
        val declined = (parseStrictJson(http.text) as? JsonObject)?.takeIf { it.string("status") == "failure" }
        if (declined != null) {
            val statusCode = declined.number("statusCode")?.toInt() ?: http.status
            val eventId = declined.string("eventId")
            logger.info("redeem:declined", "Offer redemption declined.", "eventId" to eventId, "statusCode" to statusCode)
            return RedemptionResult.Declined(declined.string("message"), statusCode, eventId)
        }
        val ok = when (val classified = classify(http)) {
            is ApiOutcome.Failed -> return redeemFailed(classified.failure)
            is ApiOutcome.Ok -> classified
        }
        val response = ok.body as? JsonObject
        if (response?.string("status") != "success") {
            return redeemFailed(Failure(FailureReason.InvalidResponse, ok.status, message = "Response is not an offer redemption."))
        }
        val eventId = response.string("eventId")
        val action = parseAction(response.obj("action"))
        logger.info("redeem:success", "Offer redeemed.", "eventId" to eventId, "action" to action.logName)
        // The workflow may have changed entitlements, so a cached decision (the paywall just redeemed from) is stale.
        clearCache()
        return RedemptionResult.Redeemed(action, eventId, response.number("statusCode")?.toInt() ?: ok.status)
    }

    private fun redeemFailed(failure: Failure): RedemptionResult {
        logger.warn("redeem:error", "Offer redemption failed.", "reason" to failure.reason.value, "status" to failure.status, "statusCode" to failure.statusCode)
        return RedemptionResult.Failed(failure)
    }

    private suspend fun callEndpointInternal(
        call: EndpointCall,
        body: JsonElement?,
        query: Map<String, String>?,
        headers: Map<String, String>?,
        timeout: Duration?,
    ): EndpointResult {
        fun refuse(message: String) = endpointFailed(call, Failure(FailureReason.RequestFailed, message = message))
        val env = environmentFromKey(config.publicKey)
            ?: return endpointFailed(call, Failure(FailureReason.NotConfigured, message = "Cannot read the environment from the public key."))
        val path = encodeEndpointPath(call.segments) ?: return refuse("The endpoint path has no segment, or an empty, '.' or '..' one.")
        val namespace = call.namespace
        if (namespace != null && isRefusedSegment(namespace)) return refuse("The namespace is empty, '.' or '..'.")
        // The JDK silently turns a GET with a body into a POST.
        if (body != null && (call.method == EndpointMethod.Get || call.method == EndpointMethod.Delete)) {
            return refuse("${call.method.value} cannot carry a body.")
        }

        val sent = LinkedHashMap(api.endpointHeaders())
        if (body != null) sent["Content-Type"] = "application/json"
        headers?.forEach { (name, value) ->
            if (name.lowercase() in SDK_OWNED_ENDPOINT_HEADERS) {
                logger.warn("endpoint:header-ignored", "The SDK sets this header itself; the host's value was dropped.", "name" to name)
            } else {
                sent.keys.removeAll { it.equals(name, ignoreCase = true) }
                sent[name] = value
            }
        }
        val scope = namespace?.let { "/namespaces/${percentEncode(it)}" }.orEmpty()
        val queryString = encodeQuery(query).let { if (it.isEmpty()) "" else "?$it" }
        val outcome = api.send(
            call.method.value, "/api/v1/envs/$env$scope/endpoints/$path$queryString", sent,
            body?.let { MosJson.encodeToString(JsonElement.serializer(), it) }, timeout, failFast = false,
        )
        val response = when (outcome) {
            is HttpOutcome.Failed -> return endpointFailed(call, outcome.failure)
            is HttpOutcome.Response -> EndpointResponse(outcome.response.status, outcome.response.headers, outcome.response.body)
        }
        val context = arrayOf("namespace" to call.namespaceJson, "path" to call.path, "method" to call.method.value, "status" to response.status)
        if (response.isSuccess) {
            logger.info("endpoint:success", "Endpoint call succeeded.", *context)
        } else {
            logger.warn("endpoint:error", "Endpoint answered with a non-2xx status.", *context)
        }
        return EndpointResult.Response(response)
    }

    private fun endpointFailed(call: EndpointCall, failure: Failure): EndpointResult {
        logger.warn(
            "endpoint:error", "Endpoint call failed.",
            "namespace" to call.namespaceJson, "path" to call.path, "method" to call.method.value, "reason" to failure.reason.value,
        )
        return EndpointResult.Failed(failure)
    }

    /** Explicit `identify()` value, then `jwtProvider`, then the persisted anon id, else mint. */
    private suspend fun resolveIdentity(): ResolvedIdentity {
        val claimKey = config.identity.claimKey
        state.withLock { explicitJwt }?.let { return ResolvedIdentity.Jwt(it, claimKey) }
        val provided = config.identity.jwtProvider?.let { provider ->
            try {
                provider()
            } catch (e: Exception) {
                rethrowIfCancelled(e)
                logger.warn("identity:provider-failed", "jwtProvider threw; continuing without a JWT.")
                null
            }
        }
        // An identify() that landed while the provider ran must not be overwritten by its answer.
        state.withLock { explicitJwt }?.let { return ResolvedIdentity.Jwt(it, claimKey) }
        if (!provided.isNullOrBlank()) return ResolvedIdentity.Jwt(provided, claimKey)
        return readAnonymousIdentifier()?.let { ResolvedIdentity.Anonymous(it) } ?: ResolvedIdentity.Mint
    }

    /**
     * Resolves the identity, then runs [apply] under [state]. A `reset()` that lands while the identity resolves
     * would let it write the signed-out identity back, so that identity is dropped and resolved again.
     */
    private suspend fun <T> resolveInLock(apply: suspend (ResolvedIdentity) -> T): Pair<ResolvedIdentity, T> {
        while (true) {
            val started = state.withLock { generation }
            val identity = resolveIdentity()
            val applied = state.withLock { if (generation == started) Applied(apply(identity)) else null }
            if (applied != null) return identity to applied.value
        }
    }

    private suspend fun readAnonymousIdentifier(): String? = readStore(ANONYMOUS_IDENTIFIER) ?: unpersistedAnonymousIdentifier

    private suspend fun readStore(key: String): String? = try {
        identityStore.get(key)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        rethrowIfCancelled(e)
        null
    }

    /** Best effort, like every store write: false when it failed. */
    private suspend fun writeStore(key: String, value: String?): Boolean = try {
        identityStore.set(key, value)
        true
    } catch (e: Exception) {
        rethrowIfCancelled(e)
        false
    }

    /** Call under [state]. Lazy so construction never touches the store. */
    private suspend fun loadIdentityState() {
        if (identityStateLoaded) return
        lastIdentityKey = readStore(LAST_IDENTITY_KEY)
        pendingPreviousAnonymousIdentifier = readStore(PENDING_PREVIOUS_ANONYMOUS_IDENTIFIER)
        // Last, so a load cancelled half-way runs again.
        identityStateLoaded = true
    }

    private suspend fun setLastIdentityKey(key: String?) {
        if (lastIdentityKey == key) return
        lastIdentityKey = key
        writeStore(LAST_IDENTITY_KEY, key)
    }

    private suspend fun setPending(id: String?) {
        droppedPreviousAnonymousIdentifier = null
        if (pendingPreviousAnonymousIdentifier == id) return
        pendingPreviousAnonymousIdentifier = id
        writeStore(PENDING_PREVIOUS_ANONYMOUS_IDENTIFIER, id)
    }

    /**
     * Call under [state]. A changed identity purges the cache and remembers an outgoing anon id for linking. Not
     * cancellable: a cancelled caller must not leave the new key recorded with the purge or the link undone.
     */
    private suspend fun noteIdentity(key: String?): Boolean = withContext(NonCancellable) {
        loadIdentityState()
        val previous = lastIdentityKey
        setLastIdentityKey(key)
        val changed = previous != null && previous != key
        if (changed) {
            logger.info("identity:changed", "Identity changed; decision cache purged.", "from" to identityKind(previous), "to" to identityKind(key))
            cache.clear("identity-changed")
            if (previous.startsWith("anon:")) {
                setPending(previous.removePrefix("anon:"))
            } else if (key?.startsWith("jwt:") == true) {
                // jwt to another jwt: the anonymous history links to the first account only.
                val dropped = pendingPreviousAnonymousIdentifier
                setPending(null)
                droppedPreviousAnonymousIdentifier = dropped
            }
        }
        dropSelfLink(key)
        changed
    }

    /** Call under [state]. Back on the anonymous id that is waiting to be linked: there is nothing to link. */
    private suspend fun dropSelfLink(key: String?) {
        val pending = pendingPreviousAnonymousIdentifier ?: return
        if (key == anonIdentityKey(pending)) setPending(null)
    }

    public companion object {
        /**
         * For a plain JVM and tests: the cache, queue and meter counts live in memory, and the anonymous identifier too
         * unless `identity.store` is set. On Android use `MOS.create(context, config)`.
         */
        public fun inMemory(config: MOSConfig): MOS = MOS(config, DeviceInfo.jvm(), Clock.System, null)
    }
}

/** One endpoint call. [path] is what logs show; `namespace` logs as JSON null for org endpoints. */
private class EndpointCall(val path: String, val segments: List<String>, val method: EndpointMethod, val namespace: String?) {
    val namespaceJson: JsonElement get() = namespace?.let(::JsonPrimitive) ?: JsonNull
}

private class Applied<T>(val value: T)

/** One `surface-decisions` outcome, shared by every coalesced caller. */
private class NetworkDecision(val decision: Decision?, val failure: Failure?, val linkedAnonymousIdentifier: Boolean)

private fun identityKindOf(key: String?): IdentityKind? = when {
    key == null -> null
    key.startsWith("anon:") -> IdentityKind.Anonymous
    else -> IdentityKind.Jwt
}

/**
 * A 4xx is dropped for good. 401 (the host may refresh an expired token) and 429, by HTTP status or envelope
 * `statusCode`, are kept for the next flush.
 */
private fun isRejection(failure: Failure): Boolean {
    // A 2xx that is not JSON never succeeds on retry either.
    if (failure.reason == FailureReason.InvalidJson) return true
    if (failure.reason == FailureReason.Unauthorized || failure.reason == FailureReason.RateLimited) return false
    val code = failure.statusCode ?: failure.status ?: return false
    return code in 400..499
}

private fun unexpected(e: Exception) = Failure(FailureReason.RequestFailed, message = "Unexpected error: ${e.javaClass.simpleName}.")

private fun saturatingSubtract(now: Long, ttl: Duration): Long {
    val ms = ttl.inWholeMilliseconds
    return try {
        Math.subtractExact(now, ms)
    } catch (_: ArithmeticException) {
        if (ms < 0) Long.MAX_VALUE else Long.MIN_VALUE
    }
}

/** A negative ttl gives an expiry in the past: the entry expires at once. */
private fun saturatingAdd(now: Long, ttl: Duration): Long {
    val ms = ttl.inWholeMilliseconds
    return try {
        Math.addExact(now, ms)
    } catch (_: ArithmeticException) {
        if (ms < 0) Long.MIN_VALUE else Long.MAX_VALUE
    }
}

internal fun Resource.toJson(): JsonObject = buildJsonObject {
    extra.forEach { (key, value) -> put(key, value) }
    put("id", id)
    meta?.let { put("meta", JsonObject(it.mapValues { (_, value) -> JsonPrimitive(value) })) }
}

/** Stable under R8, unlike the class name. */
private val Action.logName: String
    get() = when (this) {
        is Action.Navigate -> "navigate"
        is Action.Custom -> "custom"
        is Action.NoAction -> "none"
    }
