package com.syncwatch.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.syncwatch.BuildConfig
import com.syncwatch.model.*
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import java.net.URI

private const val TAG = "SocketManager"

object SocketManager {

    private val gson = Gson()
    private lateinit var socket: Socket

    // replay=1 → new collectors immediately receive current connection state
    private val _connected = MutableSharedFlow<Boolean>(replay = 1)
    val connected = _connected.asSharedFlow()

    // All inbound event flows — replay=0, each event consumed once
    private val _roomJoined      = MutableSharedFlow<RoomJoinedPayload>(replay = 0)
    private val _roomError       = MutableSharedFlow<String>(replay = 0)
    private val _userJoined      = MutableSharedFlow<UserInfo>(replay = 0)
    private val _userLeft        = MutableSharedFlow<UserInfo>(replay = 0)
    private val _playbackPlay    = MutableSharedFlow<PlaybackEvent>(replay = 0)
    private val _playbackPause   = MutableSharedFlow<PlaybackEvent>(replay = 0)
    private val _playbackSeek    = MutableSharedFlow<PlaybackEvent>(replay = 0)
    private val _playbackRate    = MutableSharedFlow<PlaybackRatePayload>(replay = 0)
    private val _syncState       = MutableSharedFlow<PlaybackState>(replay = 0)
    private val _chatMessage     = MutableSharedFlow<ChatMessage>(replay = 0)
    private val _settingsUpdate  = MutableSharedFlow<RoomSettings>(replay = 0)
    private val _hostTransferred = MutableSharedFlow<HostTransferredPayload>(replay = 0)
    private val _mediaReady      = MutableSharedFlow<MediaInfo>(replay = 0)
    private val _ssOffer         = MutableSharedFlow<SdpPayload>(replay = 0)
    private val _ssAnswer        = MutableSharedFlow<SdpAnswerPayload>(replay = 0)
    private val _ssIce           = MutableSharedFlow<IceCandidatePayload>(replay = 0)
    private val _ssStopped       = MutableSharedFlow<Unit>(replay = 0)

    val roomJoined      = _roomJoined.asSharedFlow()
    val roomError       = _roomError.asSharedFlow()
    val userJoined      = _userJoined.asSharedFlow()
    val userLeft        = _userLeft.asSharedFlow()
    val playbackPlay    = _playbackPlay.asSharedFlow()
    val playbackPause   = _playbackPause.asSharedFlow()
    val playbackSeek    = _playbackSeek.asSharedFlow()
    val playbackRate    = _playbackRate.asSharedFlow()
    val syncState       = _syncState.asSharedFlow()
    val chatMessage     = _chatMessage.asSharedFlow()
    val settingsUpdate  = _settingsUpdate.asSharedFlow()
    val hostTransferred = _hostTransferred.asSharedFlow()
    val mediaReady      = _mediaReady.asSharedFlow()
    val ssOffer         = _ssOffer.asSharedFlow()
    val ssAnswer        = _ssAnswer.asSharedFlow()
    val ssIce           = _ssIce.asSharedFlow()
    val ssStopped       = _ssStopped.asSharedFlow()

    // ── Init ─────────────────────────────────────────────────────────────────

    fun init(context: Context) {
        Log.d(TAG, "init() SERVER_URL=${BuildConfig.SERVER_URL}")

        val opts = IO.Options().apply {
            // ─────────────────────────────────────────────────────────────────
            // CRITICAL: server/index.js has transports: ['websocket'] only.
            // The server REJECTS polling connections.
            // Android must use websocket-only to match.
            //
            // Previous Session 2 "fix" of arrayOf("polling","websocket") was
            // WRONG — it caused polling to be tried first, server rejected it
            // with CONNECT_ERROR, and reconnection looped until 60 s timeout.
            // ─────────────────────────────────────────────────────────────────
            transports = arrayOf("websocket")

            reconnection         = true
            reconnectionAttempts = Integer.MAX_VALUE
            reconnectionDelay    = 2000L
            reconnectionDelayMax = 10000L
            timeout              = 20000L
        }

        socket = IO.socket(URI.create(BuildConfig.SERVER_URL), opts)
        registerListeners()
        Log.d(TAG, "init() socket created, listeners registered")
    }

    // ── Connection lifecycle ──────────────────────────────────────────────────

    fun connect() {
        if (socket.connected()) {
            Log.d(TAG, "connect() already connected sid=${socket.id()} — re-emitting true")
            _connected.tryEmit(true)
            return
        }
        Log.d(TAG, "connect() calling socket.connect()")
        socket.connect()
    }

    fun disconnect() {
        Log.d(TAG, "disconnect()")
        socket.disconnect()
    }

    val isConnected: Boolean get() = socket.connected()

    // ── Outbound helpers ──────────────────────────────────────────────────────

    fun joinRoom(payload: JoinRoomPayload)                      = emit("join_room",        payload)
    fun play(roomId: String, timestamp: Double)                 = emit("playback_play",    PlaybackEventPayload(roomId, timestamp))
    fun pause(roomId: String, timestamp: Double)                = emit("playback_pause",   PlaybackEventPayload(roomId, timestamp))
    fun seek(roomId: String, timestamp: Double)                 = emit("playback_seek",    PlaybackEventPayload(roomId, timestamp))
    fun setRate(roomId: String, rate: Float)                    = emit("playback_rate",    PlaybackRatePayload(roomId, rate))
    fun requestSync(roomId: String)                             = emit("request_sync",     mapOf("roomId" to roomId))
    fun sendChat(roomId: String, text: String)                  = emit("chat_message",     ChatMessagePayload(roomId, text))
    fun updateSettings(roomId: String, settings: RoomSettings)  = emit("settings_update",  SettingsUpdatePayload(roomId, settings))
    fun transferHost(roomId: String, targetSocketId: String)    = emit("transfer_host",    TransferHostPayload(roomId, targetSocketId))
    fun sendSsOffer(roomId: String, offer: String)              = emit("ss_offer",         mapOf("roomId" to roomId, "offer" to offer))
    fun sendSsAnswer(roomId: String, answer: String)            = emit("ss_answer",        mapOf("roomId" to roomId, "answer" to answer))
    fun sendSsStopped(roomId: String)                           = emit("ss_stopped",       mapOf("roomId" to roomId))

    fun sendSsIce(roomId: String, candidate: String, to: String? = null) {
        val map = mutableMapOf("roomId" to roomId, "candidate" to candidate)
        if (to != null) map["to"] = to
        emit("ss_ice", map)
    }

    // ── Internal emit ─────────────────────────────────────────────────────────

    private fun emit(event: String, payload: Any) {
        val json = JSONObject(gson.toJson(payload))
        Log.d(TAG, "→ emit '$event' $json")
        socket.emit(event, json)
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    private fun registerListeners() {

        socket.on(Socket.EVENT_CONNECT) {
            Log.d(TAG, "✓ EVENT_CONNECT sid=${socket.id()}")
            _connected.tryEmit(true)
        }

        socket.on(Socket.EVENT_DISCONNECT) { args ->
            Log.w(TAG, "EVENT_DISCONNECT reason=${args.firstOrNull()}")
            _connected.tryEmit(false)
        }

        socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            // Log the full error so we can diagnose via adb logcat
            Log.e(TAG, "EVENT_CONNECT_ERROR: ${args.firstOrNull()}")
            _connected.tryEmit(false)
        }

        // ── Room ──────────────────────────────────────────────────────────────

        socket.on("room_joined") { args ->
            Log.d(TAG, "← room_joined raw=${args.firstOrNull()}")
            parse<RoomJoinedPayload>(args)?.let { _roomJoined.tryEmit(it) }
                ?: Log.e(TAG, "room_joined PARSE FAILED — raw was: ${args.firstOrNull()}")
        }

        socket.on("room_error") { args ->
            Log.e(TAG, "← room_error raw=${args.firstOrNull()}")
            val json = args.firstOrNull() as? JSONObject ?: return@on
            _roomError.tryEmit(json.optString("message", "Server error"))
        }

        // ── Users ─────────────────────────────────────────────────────────────

        socket.on("user_joined") { args ->
            Log.d(TAG, "← user_joined")
            parse<UserInfo>(args)?.let { _userJoined.tryEmit(it) }
        }

        socket.on("user_left") { args ->
            Log.d(TAG, "← user_left")
            parse<UserInfo>(args)?.let { _userLeft.tryEmit(it) }
        }

        // ── Playback ──────────────────────────────────────────────────────────

        socket.on("playback_play")  { args -> parsePlaybackEvent(args)?.let { _playbackPlay.tryEmit(it) } }
        socket.on("playback_pause") { args -> parsePlaybackEvent(args)?.let { _playbackPause.tryEmit(it) } }
        socket.on("playback_seek")  { args -> parsePlaybackEvent(args)?.let { _playbackSeek.tryEmit(it) } }

        socket.on("playback_rate") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            _playbackRate.tryEmit(
                PlaybackRatePayload(roomId = "", rate = json.optDouble("rate", 1.0).toFloat())
            )
        }

        socket.on("sync_state") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            // Server sends: { playback: { timestamp, state, rate, ... } }
            val pb = json.optJSONObject("playback") ?: json
            _syncState.tryEmit(
                PlaybackState(
                    timestamp = pb.optDouble("timestamp", 0.0),
                    isPlaying = pb.optString("state") == "playing",
                    rate      = pb.optDouble("rate", 1.0).toFloat(),
                    serverTs  = System.currentTimeMillis()
                )
            )
        }

        // ── Chat & settings ───────────────────────────────────────────────────

        socket.on("chat_message") { args ->
            parse<ChatMessage>(args)?.let { _chatMessage.tryEmit(it) }
        }

        socket.on("settings_update") { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            val s = json.optJSONObject("settings") ?: json
            runCatching { gson.fromJson(s.toString(), RoomSettings::class.java) }
                .getOrNull()?.let { _settingsUpdate.tryEmit(it) }
        }

        socket.on("host_transferred") { args ->
            parse<HostTransferredPayload>(args)?.let { _hostTransferred.tryEmit(it) }
        }

        // ── Media ─────────────────────────────────────────────────────────────

        socket.on("media_ready") { args ->
            Log.d(TAG, "← media_ready")
            parse<MediaInfo>(args)?.let { _mediaReady.tryEmit(it) }
        }

        // ── Screen share ──────────────────────────────────────────────────────

        socket.on("ss_offer")   { args -> parse<SdpPayload>(args)?.let          { _ssOffer.tryEmit(it) } }
        socket.on("ss_answer")  { args -> parse<SdpAnswerPayload>(args)?.let    { _ssAnswer.tryEmit(it) } }
        socket.on("ss_ice")     { args -> parse<IceCandidatePayload>(args)?.let { _ssIce.tryEmit(it) } }
        socket.on("ss_stopped") {         _ssStopped.tryEmit(Unit) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private inline fun <reified T> parse(args: Array<Any>): T? = runCatching {
        val json = args.firstOrNull() as? JSONObject ?: return null
        gson.fromJson(json.toString(), T::class.java)
    }.getOrElse {
        Log.e(TAG, "parse<${T::class.simpleName}> failed: ${it.message}")
        null
    }

    private fun parsePlaybackEvent(args: Array<Any>): PlaybackEvent? {
        val json = args.firstOrNull() as? JSONObject ?: return null
        return PlaybackEvent(
            state = PlaybackState(
                timestamp = json.optDouble("timestamp", 0.0),
                serverTs  = System.currentTimeMillis()
            ),
            triggeredBy = json.optString("by").takeIf { it.isNotEmpty() }
        )
    }
}

// ── Extra payload types ────────────────────────────────────────────────────────

data class HostTransferredPayload(val newHostId: String, val newHostNickname: String)
data class SdpPayload(val offer: String)
data class SdpAnswerPayload(val answer: String, val from: String)
data class IceCandidatePayload(val candidate: String, val from: String)
