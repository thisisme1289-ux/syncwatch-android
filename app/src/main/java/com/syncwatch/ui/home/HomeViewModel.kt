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

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _navigateToWatch = MutableSharedFlow<RoomJoinedPayload>(extraBufferCapacity = 1)
    val navigateToWatch: SharedFlow<RoomJoinedPayload> = _navigateToWatch.asSharedFlow()

    /** hostToken stays in memory only — never written to disk. */
    var pendingHostToken: String? = null
        private set

    private var activeJob: Job? = null

    // ── Create Room ───────────────────────────────────────────────────────────

    fun createRoom(nickname: String, password: String?, mode: String) {
        if (nickname.isBlank()) {
            _uiState.value = HomeUiState.Error("Nickname cannot be empty")
            return
        }
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.value = HomeUiState.Loading

            val response = runCatching {
                ApiClient.api.createRoom(
                    CreateRoomRequest(
                        nickname = nickname.trim(),
                        password = password?.takeIf { it.isNotBlank() },
                        mode     = mode
                    )
                )
            }.getOrElse {
                _uiState.value = HomeUiState.Error("Network error: ${it.message}")
                return@launch
            }

            if (!response.isSuccessful) {
                _uiState.value = HomeUiState.Error("Server error (${response.code()})")
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

    // ── Join Room ─────────────────────────────────────────────────────────────

    fun joinRoom(code: String, nickname: String, password: String?) {
        if (nickname.isBlank()) { _uiState.value = HomeUiState.Error("Nickname cannot be empty"); return }
        if (code.isBlank())     { _uiState.value = HomeUiState.Error("Room code cannot be empty"); return }

        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.value = HomeUiState.Loading

            val lookupResponse = runCatching {
                ApiClient.api.getRoom(code.trim().uppercase())
            }.getOrElse {
                _uiState.value = HomeUiState.Error("Network error: ${it.message}")
                return@launch
            }

            when (lookupResponse.code()) {
                404          -> { _uiState.value = HomeUiState.Error("Room not found. Check the code."); return@launch }
                !in 200..299 -> { _uiState.value = HomeUiState.Error("Server error (${lookupResponse.code()})"); return@launch }
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

    // ── Core connect → join flow ───────────────────────────────────────────────
    //
    // Correct order:
    //   1. connect()
    //   2. Wait for EVENT_CONNECT  (up to 60 s — Render cold-start can take 50 s)
    //   3. Start room_joined + room_error collectors   ← AFTER connect, BEFORE emit
    //      (MutableSharedFlow replay=0: events emitted before a collector subscribes
    //       are silently dropped. We must subscribe before emitting join_room.)
    //   4. Emit join_room
    //   5. Wait for whichever collector fires first (up to 15 s)
    //
    // WHY NOT start collectors before connect():
    //   The 15 s result timeout would include the cold-start wait, so it could
    //   expire before room_joined arrives. Starting collectors after connection
    //   gives them the full 15 s for the actual join exchange.

    private suspend fun connectAndJoin(
        roomId:    String,
        nickname:  String,
        password:  String?,
        hostToken: String? = null
    ) {
        // Step 1 — connect
        SocketManager.connect()

        // Step 2 — wait for EVENT_CONNECT
        val connected = runCatching {
            withTimeout(60_000L) {
                SocketManager.connected.filter { it }.first()
            }
        }.isSuccess

        if (!connected) {
            _uiState.value = HomeUiState.Error(
                "Could not connect to server.\n" +
                "The server may be starting up — wait 30 s and try again."
            )
            return
        }

        // Step 3 — register result collectors (BEFORE emitting join_room)
        var resolved = false

        val joinedJob = viewModelScope.launch {
            val payload = runCatching {
                withTimeout(15_000L) { SocketManager.roomJoined.first() }
            }.getOrElse { return@launch }

            if (!resolved) {
                resolved = true
                _uiState.value = HomeUiState.Idle
                _navigateToWatch.emit(payload)
            }
        }

        val errorJob = viewModelScope.launch {
            val message = runCatching {
                withTimeout(15_000L) { SocketManager.roomError.first() }
            }.getOrElse { return@launch }

            if (!resolved) {
                resolved = true
                _uiState.value = HomeUiState.Error(message)
                joinedJob.cancel()
            }
        }

        // Step 4 — emit join_room now that collectors are live
        SocketManager.joinRoom(
            JoinRoomPayload(
                roomId    = roomId,
                nickname  = nickname,
                password  = password,
                hostToken = hostToken
            )
        )

        // Step 5 — wait for outcome
        joinedJob.join()
        errorJob.cancel()

        if (!resolved) {
            _uiState.value = HomeUiState.Error(
                "Server connected but did not respond to join request.\n" +
                "Check that the room still exists and try again."
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeJob?.cancel()
    }
}

// ── UI state ──────────────────────────────────────────────────────────────────

sealed class HomeUiState {
    object Idle : HomeUiState()
    object Loading : HomeUiState()
    data class NeedsPassword(val roomId: String) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}
