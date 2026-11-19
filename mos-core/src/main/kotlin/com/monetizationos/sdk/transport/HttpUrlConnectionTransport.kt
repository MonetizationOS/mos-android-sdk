package com.monetizationos.sdk.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The default transport: `HttpURLConnection` keeps core free of client libraries. Cancellation resumes the
 * caller at once and disconnects, which aborts the blocked IO thread, so the SDK's timeout bounds connect,
 * send and read together.
 */
public class HttpUrlConnectionTransport internal constructor(private val io: Executor) : HttpTransport {
    public constructor() : this(Dispatchers.IO.asExecutor())

    override suspend fun send(request: HttpRequest): HttpResponse {
        val connection = URI(request.url).toURL().openConnection() as HttpURLConnection
        val cancelled = AtomicBoolean(false)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                cancelled.set(true)
                // disconnect() can wait on a lock the reading thread holds, so never on the cancelling thread.
                io.execute { connection.disconnect() }
            }
            io.execute {
                val result = try {
                    Result.success(execute(connection, request, cancelled))
                } catch (e: Throwable) {
                    Result.failure(e)
                } finally {
                    connection.disconnect()
                }
                continuation.resumeWith(result)
            }
        }
    }

    private fun execute(connection: HttpURLConnection, request: HttpRequest, cancelled: AtomicBoolean): HttpResponse {
        // A timeout that fired while this waited for an IO thread: the caller has its answer, so send nothing.
        if (cancelled.get()) throw InterruptedIOException("Request cancelled before it was sent.")
        val timeout = request.timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        // The JDK refuses PATCH with a ProtocolException (Android accepts it); the SDK reports requestFailed.
        connection.requestMethod = request.method
        connection.connectTimeout = timeout
        connection.readTimeout = timeout
        connection.useCaches = false
        // A redirect is returned as the response: following it would re-send the key and the user JWT to another host.
        connection.instanceFollowRedirects = false
        request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        request.body?.let { body ->
            val bytes = body.encodeToByteArray()
            connection.doOutput = true
            // Streaming mode also stops the JDK silently re-sending a POST over a stale keep-alive socket.
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
        }
        val status = connection.responseCode
        val headers = connection.headerFields.orEmpty()
            .mapNotNull { (name, values) -> name?.let { it to values.orEmpty().joinToString(", ") } }
            .toMap()
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        return HttpResponse(status, stream?.use { readUnlessCancelled(it, cancelled) } ?: ByteArray(0), headers)
    }

    /** A body that trickles in never trips the socket read timeout, so stop between chunks once cancelled. */
    private fun readUnlessCancelled(input: InputStream, cancelled: AtomicBoolean): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (cancelled.get()) throw InterruptedIOException("Request cancelled.")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
