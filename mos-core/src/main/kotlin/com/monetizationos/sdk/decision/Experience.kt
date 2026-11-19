package com.monetizationos.sdk.decision

import com.monetizationos.sdk.EmptyJsonObject
import com.monetizationos.sdk.MosJson
import com.monetizationos.sdk.obj
import com.monetizationos.sdk.string
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.time.Instant

internal const val OFFER_SLOTS_KEY = "mos-offer-slots"

/**
 * A resolved experience a workflow put in an open properties map: native JSON (an `app` experience) or
 * web HTML (an `html` experience). The SDK does not know the key; the host names it.
 */
public sealed interface Experience {
    public val offers: List<OfferSlot>

    /** [content] is the experience's JSON without `mos-offer-slots`; [schema] is set for an `element` item. */
    @ConsistentCopyVisibility
    public data class Native internal constructor(
        val content: JsonObject,
        val schema: String?,
        override val offers: List<OfferSlot>,
    ) : Experience {
        /**
         * [content] as [T], unknown keys ignored.
         * @throws kotlinx.serialization.SerializationException when it does not match.
         */
        public fun <T> decode(deserializer: DeserializationStrategy<T>): T = MosJson.decodeFromJsonElement(deserializer, content)

        /** [decode] with the serializer inferred. */
        public inline fun <reified T> decode(): T = decode(serializer<T>())
    }

    /** Server-rendered HTML; offers are already in the markup, so [offers] is empty. */
    @ConsistentCopyVisibility
    public data class Web internal constructor(val html: String) : Experience {
        override val offers: List<OfferSlot> get() = emptyList()
    }

    public companion object {
        /** Null when [value] is not a resolved experience (including an empty object: nothing bound). */
        public fun decode(value: JsonElement?): Experience? = when (value) {
            is JsonObject -> if (value.isEmpty()) null else native(value, schema = null)
            is JsonArray -> decodeWebContent(value)
            else -> null
        }
    }
}

@ConsistentCopyVisibility
public data class OfferSlot internal constructor(val type: String, val offer: Offer?)

/**
 * An offer bound to a slot. [expiresAt] is the token's unverified `expirationMsUtc`. [storeProductId] is
 * `presentation.storeProductId`, the one presentation key the SDK reads. Redeem with `mos.redeem(token)`.
 */
@ConsistentCopyVisibility
public data class Offer internal constructor(
    val id: String,
    val presentation: JsonObject,
    val redemptionSchema: String?,
    val token: String,
    val expiresAt: Instant?,
    val storeProductId: String?,
)

/** Decodes a `mos-offer-slots` array. Malformed slots are skipped; a malformed offer leaves its slot's offer null. */
internal fun offerSlots(value: JsonElement?): List<OfferSlot> =
    (value as? JsonArray).orEmpty().mapNotNull { slot ->
        val obj = slot as? JsonObject ?: return@mapNotNull null
        val type = obj.string("type") ?: return@mapNotNull null
        OfferSlot(type, obj.obj("offer")?.let(::parseOffer))
    }

private fun parseOffer(offer: JsonObject): Offer? {
    val id = offer.string("id") ?: return null
    val token = offer.string("token") ?: return null
    val presentation = offer.obj("presentation") ?: EmptyJsonObject
    return Offer(
        id = id,
        presentation = presentation,
        redemptionSchema = offer.string("redemptionSchema"),
        token = token,
        expiresAt = decodeOfferToken(token)?.expirationMsUtc?.let(Instant::ofEpochMilli),
        storeProductId = presentation.string("storeProductId"),
    )
}

private fun native(content: JsonObject, schema: String?): Experience.Native =
    Experience.Native(JsonObject(content - OFFER_SLOTS_KEY), schema, offerSlots(content[OFFER_SLOTS_KEY]))

/** The `web_content` facet: all html/text items, or exactly one element item. */
private fun decodeWebContent(items: JsonArray): Experience? {
    if (items.isEmpty()) return null
    val objects = items.map { it as? JsonObject ?: return null }
    if (objects.size == 1 && objects[0].string("type") == "element") {
        val element = objects[0]
        val schema = element.string("schema") ?: return null
        val props = element.obj("props") ?: return null
        return native(props, schema)
    }
    val html = StringBuilder()
    for (item in objects) {
        val content = item.string("content") ?: return null
        when (item.string("type")) {
            "html" -> html.append(content)
            "text" -> html.append(escapeHtml(content))
            else -> return null
        }
    }
    return Experience.Web(html.toString())
}

internal fun escapeHtml(text: String): String = buildString(text.length) {
    for (c in text) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}
