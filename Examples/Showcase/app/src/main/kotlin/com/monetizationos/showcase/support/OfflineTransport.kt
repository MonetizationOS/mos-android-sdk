package com.monetizationos.showcase.support

import com.monetizationos.sdk.transport.HttpRequest
import com.monetizationos.sdk.transport.HttpResponse
import com.monetizationos.sdk.transport.HttpTransport
import java.io.IOException

/** The Offline screen's switch. Not persisted: every launch starts online. */
class OfflineSwitch {
    @Volatile var isOn: Boolean = false
}

/**
 * Fails every request like airplane mode while the switch is on, so the SDK's offline path can be
 * tried without touching the device's network. Wraps the fixture transport or the real one.
 */
class OfflineTransport(private val base: HttpTransport, private val offline: OfflineSwitch) : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse {
        if (offline.isOn) throw IOException("Offline: the Offline switch is on")
        return base.send(request)
    }
}
