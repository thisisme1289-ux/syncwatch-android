package com.syncwatch.model

import java.io.Serializable

/**
 * Mirrors the `playback` object the server includes in `room_joined` and `sync_state`.
 */
data class PlaybackState(
    val timestamp: Double = 0.0,
    val isPlaying: Boolean = false,
    val rate: Float = 1.0f,
    /** Server wall-clock time (unix ms) recorded when this state was sent. */
    val serverTs: Long = 0L
) : Serializable

/**
 * Wraps a [PlaybackState] with who triggered the change, for "Paused by X" toasts.
 */
data class PlaybackEvent(
    val state: PlaybackState,
    val triggeredBy: String? = null
) : Serializable
