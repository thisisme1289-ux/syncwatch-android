package com.syncwatch.ui.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.syncwatch.model.*
import com.syncwatch.network.SocketManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class WatchViewModel(
    val initialPayload: RoomJoinedPayload,
    val hostToken: String?
) : ViewModel() {

    val roomId: String = initialPayload.roomId
    val mode: String   = initialPayload.mode
    val isHost: Boolean get() = _isHost.value
    val mySocketId: String = initialPayload.mySocketId

    // ── Core state ────────────────────────────────────────────────────────

    private val _isHost      = MutableStateFlow(initialPayload.isHost)
    private val _users       = MutableStateFlow(initialPayload.users.toMutableList())
    private val _chat        = MutableStateFlow(initialPayload.chat.toMutableList())
    private val _settings    = MutableStateFlow(initialPayload.settings)
    private val _mediaInfo   = MutableStateFlow(initialPayload.media)
    private val _playback    = MutableStateFlow(initialPayload.playback)
    private val _connected   = MutableStateFlow(SocketManager.isConnected)

    val isHostFlow: StateFlow<Boolean>            = _isHost.asStateFlow()
    val users:      StateFlow<List<UserInfo>>     = _users.asStateFlow()
    val chat:       StateFlow<List<ChatMessage>>  = _chat.asStateFlow()
    val settings:   StateFlow<RoomSettings>       = _settings.asStateFlow()
    val mediaInfo:  StateFlow<MediaInfo?>         = _mediaInfo.asStateFlow()
    val playback:   StateFlow<PlaybackState>      = _playback.asStateFlow()
    val connected:  StateFlow<Boolean>            = _connected.asStateFlow()

    // ── One-shot UI events ────────────────────────────────────────────────

    private val _toastEvent   = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val _leaveEvent   = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _ssStartEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val _ssStopEvent  = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val toastEvent:   SharedFlow<String> = _toastEvent.asSharedFlow()
    val leaveEvent:   SharedFlow<Unit>   = _leaveEvent.asSharedFlow()
    val ssStartEvent: SharedFlow<Unit>   = _ssStartEvent.asSharedFlow()
    val ssStopEvent:  SharedFlow<Unit>   = _ssStopEvent.asSharedFlow()

    // ── Playback event flows forwarded to PlayerController ────────────────

    val playbackPlayEvent  = SocketManager.playbackPlay.shareIn(viewModelScope, SharingStarted.Eagerly)
    val playbackPauseEvent = SocketManager.playbackPause.shareIn(viewModelScope, SharingStarted.Eagerly)
    val playbackSeekEvent  = SocketManager.playbackSeek.shareIn(viewModelScope, SharingStarted.Eagerly)
    val playbackRateEvent  = SocketManager.playbackRate.shareIn(viewModelScope, SharingStarted.Eagerly)
    val syncStateEvent     = SocketManager.syncState.shareIn(viewModelScope, SharingStarted.Eagerly)

    init {
        observeSocketEvents()
    }

    // ── Actions: playback (called by PlayerController) ────────────────────

    fun onLocalPlay(timestamp: Double) =
        SocketManager.play(roomId, timestamp)

    fun onLocalPause(timestamp: Double) =
        SocketManager.pause(roomId, timestamp)

    fun onLocalSeek(timestamp: Double) =
        SocketManager.seek(roomId, timestamp)

    fun onLocalRateChange(rate: Float) =
        SocketManager.setRate(roomId, rate)

    fun requestSync() =
        SocketManager.requestSync(roomId)

    // ── Actions: chat ─────────────────────────────────────────────────────

    fun sendChat(text: String) {
        if (text.isBlank()) return
        SocketManager.sendChat(roomId, text.trim())
    }

    // ── Actions: host settings ────────────────────────────────────────────

    fun updateSettings(settings: RoomSettings) {
        if (!isHost) return
        SocketManager.updateSettings(roomId, settings)
    }

    fun transferHost(targetSocketId: String) {
        if (!isHost) return
        SocketManager.transferHost(roomId, targetSocketId)
    }

    // ── Actions: room ─────────────────────────────────────────────────────

    fun leaveRoom() {
        SocketManager.disconnect()
        viewModelScope.launch { _leaveEvent.emit(Unit) }
    }

    // ── Observe socket events ─────────────────────────────────────────────

    private fun observeSocketEvents() {
        viewModelScope.launch {
            SocketManager.connected.collect { _connected.value = it }
        }

        viewModelScope.launch {
            SocketManager.userJoined.collect { user ->
                _users.update { list -> (list + user).distinctBy { it.socketId }.toMutableList() }
                _toastEvent.emit("${user.nickname} joined")
            }
        }

        viewModelScope.launch {
            SocketManager.userLeft.collect { user ->
                _users.update { list -> list.filterNot { it.socketId == user.socketId }.toMutableList() }
                _toastEvent.emit("${user.nickname} left")
            }
        }

        viewModelScope.launch {
            SocketManager.chatMessage.collect { msg ->
                _chat.update { list -> (list + msg).toMutableList() }
            }
        }

        viewModelScope.launch {
            SocketManager.settingsUpdate.collect { settings ->
                _settings.value = settings
            }
        }

        viewModelScope.launch {
            SocketManager.hostTransferred.collect { payload ->
                val iAmNewHost = payload.newHostId == mySocketId
                _isHost.value = iAmNewHost
                _users.update { list ->
                    list.map { u ->
                        u.copy(isHost = u.socketId == payload.newHostId)
                    }.toMutableList()
                }
                if (iAmNewHost) _toastEvent.emit("You are now the host")
                else _toastEvent.emit("${payload.newHostNickname} is now the host")
            }
        }

        viewModelScope.launch {
            SocketManager.mediaReady.collect { info ->
                _mediaInfo.value = info
            }
        }

        viewModelScope.launch {
            SocketManager.ssStopped.collect {
                _ssStopEvent.emit(Unit)
            }
        }

        viewModelScope.launch {
            SocketManager.playbackPlay.collect { event ->
                event.triggeredBy?.let { _toastEvent.emit("▶ Playing (${it})") }
                _playback.update { it.copy(isPlaying = true, timestamp = event.state.timestamp) }
            }
        }

        viewModelScope.launch {
            SocketManager.playbackPause.collect { event ->
                event.triggeredBy?.let { _toastEvent.emit("⏸ Paused (${it})") }
                _playback.update { it.copy(isPlaying = false, timestamp = event.state.timestamp) }
            }
        }

        viewModelScope.launch {
            SocketManager.playbackSeek.collect { event ->
                _playback.update { it.copy(timestamp = event.state.timestamp) }
            }
        }

        viewModelScope.launch {
            SocketManager.syncState.collect { state ->
                _playback.value = state
            }
        }
    }

    // ── ViewModel factory ──────────────────────────────────────────────────

    class Factory(
        private val payload: RoomJoinedPayload,
        private val hostToken: String?
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WatchViewModel(payload, hostToken) as T
    }
}
