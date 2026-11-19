package com.monetizationos.sdk

/** Wall clock in epoch milliseconds. Injectable so tests control cache and queue ages. */
internal fun interface Clock {
    fun nowMs(): Long

    companion object {
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
