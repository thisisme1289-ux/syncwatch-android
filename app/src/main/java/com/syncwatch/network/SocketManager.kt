package com.syncwatch.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.syncwatch.BuildConfig
import com.syncwatch.model.*
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import java.net.URI

private const val TAG = "SocketManager"

object SocketManager {

    private val gson = Gson()
    private lateinit var socket: Socket

    // ── Connection state ────────────────────────────────────────────────────

    private val _connected = MutableSharedFlow<Boolean>(replay = 1)
    val connected = _connected.asSharedFlow()

    // ── Inbound event flows (one per server → client event) ─────────────────

    private val _roomJoined    = MutableSharedFlow<RoomJoinedPayload>(replay = 0)
    private val _roomError     = MutableSharedFlow<String>(replay = 0)
    private val _userJoined    = MutableSharedFlow<UserInfo>(replay = 0)
    private val _userLeft      = MutableSharedFlow<UserInfo>(replay = 0)
    private val _playbackPlay  = MutableSharedFlow<PlaybackEvent>(replay = 0)
    private val _playbackPause = MutableSharedFlow<PlaybackEvent>(replay = 0)
    private val _playbackSeek  = MutableSharedFlow<PlaybackEvent>(replay = 0)
    private val _playbackRate  = MutableSharedFlow<PlaybackRatePayload>(replay = 0)
    private val _syncState     = MutableSharedFlow<PlaybackState>(replay = 0)
    private val _chatMessage   = MutableSharedFlow<ChatMessage>(replay = 0)
    private val _settingsUpdate = MutableSharedFlow<RoomSettings>(replay = 0)
    private val _hostTransferred = MutableSharedFlow<HostTransferredPayload>(replay = 0)
    private val _mediaReady    = MutableSharedFlow<MediaInfo>(replay = 0)
    private val _ssOffer       = MutableSharedFlow<SdpPayload>(replay = 0)
    private val _ssAnswer      = MutableSharedFlow<SdpAnswerPayload>(replay = 0)
    private val _ssIce         = MutableSharedFlow<IceCandidatePayload>(replay = 0)
    private val _ssStopped     = MutableSharedFlow<Unit>(replay = 0)

    val roomJoined     = _roomJoined.asSharedFlow()
    val roomError      = _roomError.asSharedFlow()
    val userJoined     = _userJoined.asSharedFlow()
    val userLeft       = _userLeft.asSharedFlow()
    val playbackPlay   = _playbackPlay.asSharedFlow()
    val playbackPause  = _playbackPause.asSharedFlow()
    val playbackSeek   = _playbackSeek.asSharedFlow()
    val playbackRate   = _playbackRate.asSharedFlow()
    val syncState      = _syncState.asSharedFlow()
    val chatMessage    = _chatMessage.asSharedFlow()
    val settingsUpdate = _settingsUpdate.asSharedFlow()
    val hostTransferred = _hostTransferred.asSharedFlow()
    val mediaReady     = _mediaReady.asSharedFlow()
    val ssOffer        = _ssOffer.asSharedFlow()
    val ssAnswer       = _ssAnswer.asSharedFlow()
    val ssIce          = _ssIce.asSharedFlow()
    val ssStopped      = _ssStopped.asSharedFlow()

    // ── Init ────────────────────────────────────────────────────────────────

    fun init(context: Context) {
        val opts = IO.Options().apply {
            transports = arrayOf("websocket")   // RULE: WebSocket only, no polling
            reconnection = true
            reconnectionAttempts = Int.MAX_VALUE
            reconnectionDelay = 1000
            reconnectionDelayMax = 5000
        }

        socket = IO.socket(URI.create(BuildConfig.SERVER_URL), opts)
        registerListeners()
    }

    // ── Connection lifecycle ─────────────────────────────────────────────────

    fun connect() {
        if (!socket.connected()) socket.connect()
    }

    fun disconnect() {
        socket.disconnect()
    }

    val isConnected: Boolean get() = socket.connected()

    // ── Emit helpers (client → server) ──────────────────────────────────────

    fun joinRoom(payload: JoinRoomPayload) =
        emit("join_room", payload)

    fun play(roomId: String, timestamp: Double) =
        emit("playback_play", PlaybackEventPayload(roomId, timestamp))

    fun pause(roomId: String, timestamp: Double) =
        emit("playback_pause", PlaybackEventPayload(roomId, timestamp))

    fun seek(roomId: String, timestamp: Double) =
        emit("playback_seek", PlaybackEventPayload(roomId, timestamp))

    fun setRate(roomId: String, rate: Float) =
        emit("playback_rate", PlaybackRatePayload(roomId, rate))

    fun requestSync(roomId: String) =
        emit("request_sync", mapOf("roomId" to roomId))

    fun sendChat(roomId: String, text: String) =
        emit("chat_message", ChatMessagePayload(roomId, text))

    fun updateSettings(roomId: String, settings: RoomSettings) =
        emit("settings_update", SettingsUpdatePayload(roomId, settings))

    fun transferHost(roomId: String, targetSocketId: String) =
        emit("transfer_host", TransferHostPayload(roomId, targetSocketId))

    fun sendSsOffer(roomId: String, offer: String) =
        emit("ss_offer", mapOf("roomId" to roomId, "offer" to offer))

    fun sendSsAnswer(roomId: String, answer: String) =
        emit("ss_answer", mapOf("roomId" to roomId, "answer" to answer))

    fun sendSsIce(roomId: String, candidate: String, to: String? = null) {
        val map = mutableMapOf("roomId" to roomId, "candidate" to candidate)
        if (to != null) map["to"] = to
        emit("ss_ice", map)
    }

    fun sendSsStopped(roomId: String) =
        emit("ss_stopped", mapOf("roomId" to roomId))

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun emit(event: String, payload: Any) {
        if (!socket.connected()) {
            Log.w(TAG, "emit($event) called while disconnected — queued by Socket.IO")
        }
        val json = JSONObject(gson.toJson(payload))
        socket.emit(event, json)
    }

    @Suppress("UNCHECKED_CAST")
    private fun registerListeners() {

        socket.on(Socket.EVENT_CONNECT) {
            Log.d(TAG, "connected")
            _connected.tryEmit(true)
        }

        socket.on(Socket.EVENT_DISCONNECT) {
            Log.d(TAG, "disconnected")
            _connected.tryEmit(false)
        }

        socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            Log.e(TAG, "connect_error: ${args.firstOrNull()}")
            _connected.tryEmit(false)
        }

        socket.on("room_joined") { args ->
            parse<RoomJoinedPayload>(args)?.let { _roomJoined.tryEmit(it) }
        }

        socket.on("room_error") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            _roomError.tryEmit(json.optString("message", "Unknown error"))
        }

        socket.on("user_joined") { args ->
            parse<UserInfo>(args)?.let { _userJoined.tryEmit(it) }
        }

        socket.on("user_left") { args ->
            parse<UserInfo>(args)?.let { _userLeft.tryEmit(it) }
        }

        socket.on("playback_play") { args ->
            parsePlaybackEvent(args)?.let { _playbackPlay.tryEmit(it) }
        }

        socket.on("playback_pause") { args ->
            parsePlaybackEvent(args)?.let { _playbackPause.tryEmit(it) }
        }

        socket.on("playback_seek") { args ->
            parsePlaybackEvent(args)?.let { _playbackSeek.tryEmit(it) }
        }

        socket.on("playback_rate") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            _playbackRate.tryEmit(
                PlaybackRatePayload(
                    roomId = "",            // not included in server broadcast
                    rate = json.optDouble("rate", 1.0).toFloat()
                )
            )
        }

        socket.on("sync_state") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            val pb = json.optJSONObject("playback") ?: return@on
            _syncState.tryEmit(
                PlaybackState(
                    timestamp = pb.optDouble("timestamp", 0.0),
                    isPlaying = pb.optBoolean("isPlaying", false),
                    rate = pb.optDouble("rate", 1.0).toFloat(),
                    serverTs = System.currentTimeMillis()
                )
            )
        }

        socket.on("chat_message") { args ->
            parse<ChatMessage>(args)?.let { _chatMessage.tryEmit(it) }
        }

        socket.on("settings_update") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            val s = json.optJSONObject("settings") ?: return@on
            parse<RoomSettings>(s.toString().wrapInArgs())?.let { _settingsUpdate.tryEmit(it) }
        }

        socket.on("host_transferred") { args ->
            parse<HostTransferredPayload>(args)?.let { _hostTransferred.tryEmit(it) }
        }

        socket.on("media_ready") { args ->
            parse<MediaInfo>(args)?.let { _mediaReady.tryEmit(it) }
        }

        socket.on("ss_offer") { args ->
            parse<SdpPayload>(args)?.let { _ssOffer.tryEmit(it) }
        }

        socket.on("ss_answer") { args ->
            parse<SdpAnswerPayload>(args)?.let { _ssAnswer.tryEmit(it) }
        }

        socket.on("ss_ice") { args ->
            parse<IceCandidatePayload>(args)?.let { _ssIce.tryEmit(it) }
        }

        socket.on("ss_stopped") {
            _ssStopped.tryEmit(Unit)
        }
    }

    private inline fun <reified T> parse(args: Array<Any>): T? {
        return try {
            val json = args.firstOrNull() as? JSONObject ?: return null
            gson.fromJson(json.toString(), T::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ${T::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun parsePlaybackEvent(args: Array<Any>): PlaybackEvent? {
        val json = args.firstOrNull() as? JSONObject ?: return null
        val ts = json.optDouble("timestamp", 0.0)
        val by = json.optString("by").takeIf { it.isNotEmpty() }
        return PlaybackEvent(
            state = PlaybackState(timestamp = ts, serverTs = System.currentTimeMillis()),
            triggeredBy = by
        )
    }

    /** Wraps a raw JSON string in a single-element array so parse<T> can read it. */
    private fun String.wrapInArgs(): Array<Any> = arrayOf(JSONObject(this))
}

// ── Extra payload types only used by SocketManager ──────────────────────────

data class HostTransferredPayload(
    val newHostId: String,
    val newHostNickname: String
)

data class SdpPayload(val offer: String)
data class SdpAnswerPayload(val answer: String, val from: String)
data class IceCandidatePayload(val candidate: String, val from: String)
