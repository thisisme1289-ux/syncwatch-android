package com.syncwatch.model

/**
 * Mirrors the `playback` object the server includes in `room_joined` and `sync_state`.
 */
data class PlaybackState(
    /** Current position in seconds. */
    val timestamp: Double = 0.0,

    /** True if the video is playing, false if paused. */
    val isPlaying: Boolean = false,

    /** Playback speed multiplier (1.0 = normal). */
    val rate: Float = 1.0f,

    /** Server wall-clock time (unix ms) when this state was recorded — used to
     *  calculate drift when the app receives a delayed sync_state. */
    val serverTs: Long = 0L
)

/**
 * Wraps a [PlaybackState] with metadata about who triggered the change so the
 * UI can show "Paused by Alex" toasts without extra network round-trips.
 */
data class PlaybackEvent(
    val state: PlaybackState,
    val triggeredBy: String? = null     // nickname, null if self-originated
)
