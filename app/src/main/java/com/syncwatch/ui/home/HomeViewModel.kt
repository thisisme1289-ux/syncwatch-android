package com.syncwatch.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syncwatch.model.CreateRoomRequest
import com.syncwatch.model.CreateRoomResponse
import com.syncwatch.model.JoinRoomPayload
import com.syncwatch.model.RoomJoinedPayload
import com.syncwatch.model.RoomLookupResponse
import com.syncwatch.network.ApiClient
import com.syncwatch.network.SocketManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class HomeViewModel : ViewModel() {

    // ── UI state ──────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // ── Navigation event ──────────────────────────────────────────────────

    private val _navigateToWatch = MutableSharedFlow<RoomJoinedPayload>(extraBufferCapacity = 1)
    val navigateToWatch: SharedFlow<RoomJoinedPayload> = _navigateToWatch.asSharedFlow()

    // ── In-memory host token (never persisted) ────────────────────────────

    var pendingHostToken: String? = null
        private set

    // ── Active join job (so we can cancel if user presses back) ──────────

    private var joinJob: Job? = null

    // ── Public actions ────────────────────────────────────────────────────

    fun createRoom(nickname: String, password: String?, mode: String) {
        if (nickname.isBlank()) {
            _uiState.value = HomeUiState.Error("Nickname cannot be empty")
            return
        }

        joinJob?.cancel()
        joinJob = viewModelScope.launch {
            _uiState.value = HomeUiState.Loading

            val response = runCatching {
                ApiClient.api.createRoom(
                    CreateRoomRequest(
                        nickname = nickname.trim(),
                        password = password?.takeIf { it.isNotBlank() },
                        mode = mode
                    )
                )
            }.getOrElse {
                _uiState.value = HomeUiState.Error("Network error: ${it.message}")
                return@launch
            }

            if (!response.isSuccessful) {
                _uiState.value = HomeUiState.Error("Server error: ${response.code()}")
                return@launch
            }

            val body: CreateRoomResponse = response.body() ?: run {
                _uiState.value = HomeUiState.Error("Empty response from server")
                return@launch
            }

            pendingHostToken = body.hostToken
            connectAndJoin(
                roomId    = body.roomId,
                nickname  = nickname.trim(),
                password  = password?.takeIf { it.isNotBlank() },
                hostToken = body.hostToken
            )
        }
    }

    fun joinRoom(code: String, nickname: String, password: String?) {
        if (nickname.isBlank()) {
            _uiState.value = HomeUiState.Error("Nickname cannot be empty")
            return
        }
        if (code.isBlank()) {
            _uiState.value = HomeUiState.Error("Room code cannot be empty")
            return
        }

        joinJob?.cancel()
        joinJob = viewModelScope.launch {
            _uiState.value = HomeUiState.Loading

            val lookupResponse = runCatching {
                ApiClient.api.getRoom(code.trim().uppercase())
            }.getOrElse {
                _uiState.value = HomeUiState.Error("Network error: ${it.message}")
                return@launch
            }

            when (lookupResponse.code()) {
                404  -> { _uiState.value = HomeUiState.Error("Room not found. Check the code."); return@launch }
                !in 200..299 -> { _uiState.value = HomeUiState.Error("Server error: ${lookupResponse.code()}"); return@launch }
            }

            val room: RoomLookupResponse = lookupResponse.body() ?: run {
                _uiState.value = HomeUiState.Error("Empty response from server")
                return@launch
            }

            if (room.hasPassword && password.isNullOrBlank()) {
                _uiState.value = HomeUiState.NeedsPassword(room.roomId)
                return@launch
            }

            connectAndJoin(
                roomId   = room.roomId,
                nickname = nickname.trim(),
                password = password?.takeIf { it.isNotBlank() }
            )
        }
    }

    // ── Private — core connection + join flow ─────────────────────────────

    private suspend fun connectAndJoin(
        roomId:    String,
        nickname:  String,
        password:  String?,
        hostToken: String? = null
    ) {
        // Start the socket (no-op if already connected)
        SocketManager.connect()

        // FIX: use first { it } + withTimeout instead of an infinite collect loop.
        // connected has replay=1, so if already connected this returns immediately.
        val connected = runCatching {
            withTimeout(15_000L) {
                SocketManager.connected.filter { it }.first()
            }
        }.getOrElse {
            _uiState.value = HomeUiState.Error("Could not connect to server (timeout). Check your internet connection.")
            return
        }

        // Send join_room
        SocketManager.joinRoom(
            JoinRoomPayload(
                roomId    = roomId,
                nickname  = nickname,
                password  = password,
                hostToken = hostToken
            )
        )

        // Wait for room_joined OR room_error — whichever arrives first, with a timeout.
        // FIX: launch two parallel collectors, cancel both as soon as one fires.
        var resolved = false

        val roomJoinedJob = viewModelScope.launch {
            val payload = withTimeout(15_000L) {
                SocketManager.roomJoined.first()
            }
            if (!resolved) {
                resolved = true
                _uiState.value = HomeUiState.Idle
                _navigateToWatch.emit(payload)
            }
        }

        val roomErrorJob = viewModelScope.launch {
            val message = withTimeout(15_000L) {
                SocketManager.roomError.first()
            }
            if (!resolved) {
                resolved = true
                _uiState.value = HomeUiState.Error(message)
            }
        }

        // Wait for whichever resolves first
        try {
            // Both jobs are racing. Join them together and cancel the loser.
            roomJoinedJob.join()
            roomErrorJob.cancel()
        } catch (_: Exception) {
            // roomJoinedJob timed out or was cancelled
            roomErrorJob.cancel()
            if (!resolved) {
                _uiState.value = HomeUiState.Error("Server did not respond. Try again.")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        joinJob?.cancel()
    }
}

// ── UI state ──────────────────────────────────────────────────────────────────

sealed class HomeUiState {
    object Idle : HomeUiState()
    object Loading : HomeUiState()
    data class NeedsPassword(val roomId: String) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}
