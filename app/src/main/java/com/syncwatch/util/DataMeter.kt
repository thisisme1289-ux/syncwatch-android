package com.syncwatch.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks HTTP and WebRTC bytes for the lifetime of the session.
 *
 * HTTP bytes are counted via [httpInterceptor] (installed in ApiClient).
 * WebRTC bytes are added manually from ScreenShareHost/Guest after reading
 * PeerConnection stats.
 *
 * Consumers observe [bytesIn] and [bytesOut] as StateFlows.
 * Call [reset] when a new room session starts.
 */
object DataMeter {

    private val _httpBytesIn  = AtomicLong(0)
    private val _httpBytesOut = AtomicLong(0)
    private val _rtcBytesIn   = AtomicLong(0)
    private val _rtcBytesOut  = AtomicLong(0)

    private val _bytesIn  = MutableStateFlow(0L)
    private val _bytesOut = MutableStateFlow(0L)

    val bytesIn:  StateFlow<Long> = _bytesIn
    val bytesOut: StateFlow<Long> = _bytesOut

    fun reset() {
        _httpBytesIn.set(0); _httpBytesOut.set(0)
        _rtcBytesIn.set(0);  _rtcBytesOut.set(0)
        _bytesIn.value = 0;  _bytesOut.value = 0
    }

    /** Call from ScreenShareHost/Guest after each PeerConnection stats read. */
    fun addRtcBytes(received: Long, sent: Long) {
        _rtcBytesIn.addAndGet(received)
        _rtcBytesOut.addAndGet(sent)
        publish()
    }

    private fun publish() {
        _bytesIn.value  = _httpBytesIn.get()  + _rtcBytesIn.get()
        _bytesOut.value = _httpBytesOut.get() + _rtcBytesOut.get()
    }

    /** OkHttp interceptor — counts request body (out) and response body (in). */
    val httpInterceptor = Interceptor { chain ->
        val request = chain.request()

        // Count outgoing bytes (request body)
        val reqBytes = request.body?.contentLength()?.takeIf { it > 0 } ?: 0L
        _httpBytesOut.addAndGet(reqBytes)

        val response: Response = chain.proceed(request)

        // Count incoming bytes (response body).
        // We peek so we don't consume the stream before Retrofit does.
        val respBytes = response.peekBody(Long.MAX_VALUE).contentLength().takeIf { it > 0 } ?: 0L
        _httpBytesIn.addAndGet(respBytes)

        publish()
        response
    }
}
