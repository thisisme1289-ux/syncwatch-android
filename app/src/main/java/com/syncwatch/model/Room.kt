package com.syncwatch.model

import com.google.gson.annotations.SerializedName
import java.io.Serializable

// ── REST responses ─────────────────────────────────────────────────────────────

data class CreateRoomRequest(
    val nickname: String,
    val password: String? = null,
    val mode: String = "local"
)

data class CreateRoomResponse(
    val roomId: String,
    // server/index.js returns "roomCode" in POST /api/rooms response
    @SerializedName("roomCode") val code: String,
    val hostToken: String
)

data class RoomLookupResponse(
    val roomId: String,
    // server/index.js returns "code" in GET /api/rooms/:id response
    val code: String,
    val mode: String,
    val hasPassword: Boolean
)

// ── Socket payloads received from server ───────────────────────────────────────
//
// server/socket.js room_joined payload (verified from source):
//   { roomId, code, mode, settings, playback, media, users, chat, isHost, mySocketId }
//
// All classes passed through a Bundle must implement Serializable.

data class RoomJoinedPayload(
    val roomId: String,
    val code: String,
    val mode: String,
    val settings: RoomSettings,
    val playback: PlaybackState,
    val media: MediaInfo?,
    val users: List<UserInfo>,
    val chat: List<ChatMessage>,
    val isHost: Boolean,
    val mySocketId: String
) : Serializable

// Server settings keys (from socket.js settings_update handler):
//   hostOnlyControl, voiceEnabled, videoEnabled, chatEnabled, dataSaver, defaultQuality
// Android uses different names internally — Gson will silently apply defaults.
// TODO: align these field names in a follow-up session.
data class RoomSettings(
    val locked: Boolean = false,
    val maxGuests: Int = 10,
    val allowChat: Boolean = true,
    val syncThreshold: Double = 2.0
) : Serializable

data class MediaInfo(
    val url: String?,
    val filename: String?
) : Serializable

data class UserInfo(
    val socketId: String,
    val nickname: String,
    val isHost: Boolean
) : Serializable

data class ChatMessage(
    val nickname: String,
    val text: String,
    val ts: Long
) : Serializable

// ── Socket payloads emitted by app ────────────────────────────────────────────

data class JoinRoomPayload(
    val roomId: String,
    val nickname: String,
    val password: String? = null,
    val hostToken: String? = null
)

data class PlaybackEventPayload(
    val roomId: String,
    val timestamp: Double
)

data class PlaybackRatePayload(
    val roomId: String,
    val rate: Float
)

data class ChatMessagePayload(
    val roomId: String,
    val text: String
)

data class SettingsUpdatePayload(
    val roomId: String,
    val settings: RoomSettings
)

data class TransferHostPayload(
    val roomId: String,
    val targetSocketId: String
)
