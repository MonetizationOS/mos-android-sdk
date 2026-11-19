package com.monetizationos.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Call first in a `catch (e: Exception)`: rethrows only when the calling coroutine is itself cancelled. A
 * [CancellationException] from host code (a `withTimeout` of its own, say) is a failure like any other.
 */
internal suspend fun rethrowIfCancelled(e: Exception) {
    if (e is CancellationException) currentCoroutineContext().ensureActive()
}
