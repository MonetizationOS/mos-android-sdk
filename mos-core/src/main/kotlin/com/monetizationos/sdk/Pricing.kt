package com.monetizationos.sdk

/**
 * Localised store prices for offers' `storeProductId`s. Android hosts implement it over Play Billing themselves.
 * A subscription's price is its base plan's, so give those as `productId:basePlanId`, as RevenueCat does.
 */
public fun interface PriceProvider {
    /** Products the store does not know are left out of the result. */
    public suspend fun prices(productIds: Set<String>): Map<String, StorePrice>
}

/** Display strings as the store formats them, e.g. "£4.99", "month", "£0.99". */
public data class StorePrice(
    val displayPrice: String,
    val displayPeriod: String? = null,
    val introductoryDisplayPrice: String? = null,
)
