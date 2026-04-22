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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {

    // ── UI state ──────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // ── One-shot navigation events ────────────────────────────────────────

    private val _navigateToWatch = MutableSharedFlow<RoomJoinedPayload>(extraBufferCapacity = 1)
    val navigateToWatch: SharedFlow<RoomJoinedPayload> = _navigateToWatch.asSharedFlow()

    // ── In-memory session state (never persisted) ─────────────────────────

    /** Kept in memory so WatchViewModel can read it once and never store it. */
    var pendingHostToken: String? = null
        private set

    // ── Public actions ────────────────────────────────────────────────────

    /**
     * Creates a new room via REST, then connects the socket and emits join_room.
     */
    fun createRoom(nickname: String, password: String?, mode: String) {
        if (nickname.isBlank()) {
            _uiState.value = HomeUiState.Error("Nickname cannot be empty")
            return
        }

        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading

            val result = runCatching {
                ApiClient.api.createRoom(
                    CreateRoomRequest(
                        nickname = nickname.trim(),
                        password = password?.takeIf { it.isNotBlank() },
                        mode = mode
                    )
                )
            }

            val response = result.getOrElse {
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
                roomId = body.roomId,
                nickname = nickname.trim(),
                password = password?.takeIf { it.isNotBlank() },
                hostToken = body.hostToken
            )
        }
    }

    /**
     * Looks up the room by code, then connects the socket and emits join_room.
     */
    fun joinRoom(code: String, nickname: String, password: String?) {
        if (nickname.isBlank()) {
            _uiState.value = HomeUiState.Error("Nickname cannot be empty")
            return
        }
        if (code.isBlank()) {
            _uiState.value = HomeUiState.Error("Room code cannot be empty")
            return
        }

        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading

            val lookupResult = runCatching {
                ApiClient.api.getRoom(code.trim().uppercase())
            }

            val lookupResponse = lookupResult.getOrElse {
                _uiState.value = HomeUiState.Error("Network error: ${it.message}")
                return@launch
            }

            if (lookupResponse.code() == 404) {
                _uiState.value = HomeUiState.Error("Room not found. Check the code and try again.")
                return@launch
            }

            if (!lookupResponse.isSuccessful) {
                _uiState.value = HomeUiState.Error("Server error: ${lookupResponse.code()}")
                return@launch
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
                roomId = room.roomId,
                nickname = nickname.trim(),
                password = password?.takeIf { it.isNotBlank() }
            )
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun connectAndJoin(
        roomId: String,
        nickname: String,
        password: String?,
        hostToken: String? = null
    ) {
        viewModelScope.launch {
            SocketManager.connect()

            // Wait for connection (collect once)
            SocketManager.connected.collect { isConnected ->
                if (!isConnected) return@collect

                SocketManager.joinRoom(
                    JoinRoomPayload(
                        roomId = roomId,
                        nickname = nickname,
                        password = password,
                        hostToken = hostToken
                    )
                )

                // Now wait for the server's room_joined or room_error
                launch {
                    SocketManager.roomJoined.collect { payload ->
                        _uiState.value = HomeUiState.Idle
                        _navigateToWatch.emit(payload)
                        // Stop collecting after first event
                        return@collect
                    }
                }

                launch {
                    SocketManager.roomError.collect { message ->
                        _uiState.value = HomeUiState.Error(message)
                        return@collect
                    }
                }

                // Stop the outer connected collector after we've sent join_room
                return@collect
            }
        }
    }
}

sealed class HomeUiState {
    object Idle : HomeUiState()
    object Loading : HomeUiState()
    data class NeedsPassword(val roomId: String) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}
