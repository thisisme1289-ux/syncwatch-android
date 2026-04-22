package com.syncwatch.ui.watch

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow

/**
 * Wraps ExoPlayer and bridges between local user gestures and incoming
 * socket playback events.
 *
 * Threading: ExoPlayer must be touched on the main thread only.
 * All methods here must be called from the main thread (Fragment lifecycle).
 *
 * Sync strategy:
 *  - When a remote event arrives we suppress re-emitting it back to the socket
 *    by setting [suppressNextEmit] = true before calling the ExoPlayer API.
 *  - Player.Listener callbacks then check the flag and clear it.
 */
class PlayerController(
    context: Context,
    private val scope: CoroutineScope,
    private val onPlay:      (timestamp: Double) -> Unit,
    private val onPause:     (timestamp: Double) -> Unit,
    private val onSeek:      (timestamp: Double) -> Unit,
    private val onRateChange:(rate: Float) -> Unit
) {

    val player: ExoPlayer = ExoPlayer.Builder(context).build()

    /** When true, the next Player.Listener callback will NOT emit to socket. */
    private var suppressNextEmit = false

    /** How far (seconds) a remote timestamp can differ before we force-seek. */
    private val syncThreshold = 2.0

    init {
        player.addListener(object : Player.Listener {

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (suppressNextEmit) { suppressNextEmit = false; return }
                val ts = player.currentPosition / 1000.0
                if (isPlaying) onPlay(ts) else onPause(ts)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    if (suppressNextEmit) { suppressNextEmit = false; return }
                    onSeek(newPosition.positionMs / 1000.0)
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                if (suppressNextEmit) { suppressNextEmit = false; return }
                onRateChange(playbackParameters.speed)
            }
        })
    }

    // ── Load media ────────────────────────────────────────────────────────

    fun loadLocalFile(uri: Uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    fun loadUrl(url: String) {
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
    }

    // ── Remote event handlers (called from ViewModel flows) ───────────────

    fun onRemotePlay(timestamp: Double) {
        suppressNextEmit = true
        seekIfNeeded(timestamp)
        player.play()
    }

    fun onRemotePause(timestamp: Double) {
        suppressNextEmit = true
        seekIfNeeded(timestamp)
        player.pause()
    }

    fun onRemoteSeek(timestamp: Double) {
        suppressNextEmit = true
        player.seekTo((timestamp * 1000).toLong())
    }

    fun onRemoteRateChange(rate: Float) {
        suppressNextEmit = true
        player.setPlaybackSpeed(rate)
    }

    /**
     * Forces full state sync from a [com.syncwatch.model.PlaybackState].
     * Called when sync_state arrives or on initial room_joined.
     */
    fun applySync(timestamp: Double, isPlaying: Boolean, rate: Float) {
        suppressNextEmit = true
        player.setPlaybackSpeed(rate)
        player.seekTo((timestamp * 1000).toLong())
        if (isPlaying) player.play() else player.pause()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun seekIfNeeded(remoteTs: Double) {
        val localTs = player.currentPosition / 1000.0
        if (Math.abs(localTs - remoteTs) > syncThreshold) {
            player.seekTo((remoteTs * 1000).toLong())
        }
    }

    val currentPositionSeconds: Double
        get() = player.currentPosition / 1000.0

    // ── Lifecycle ─────────────────────────────────────────────────────────

    fun release() {
        player.release()
    }
}
