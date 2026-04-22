package com.syncwatch.model

import java.io.Serializable

data class PlaybackState(
    val timestamp: Double  = 0.0,
    val isPlaying: Boolean = false,
    val rate: Float        = 1.0f,
    val serverTs: Long     = 0L
) : Serializable

data class PlaybackEvent(
    val state: PlaybackState,
    val triggeredBy: String? = null
) : Serializable
