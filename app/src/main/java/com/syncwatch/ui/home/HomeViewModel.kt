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

    /** hostToken stays in memory, never written to disk. */
    var pendingHostToken: String? = null
        private set

    private var activeJob: Job? = null

    // ── Create room ───────────────────────────────────────────────────────

    fun createRoom(nickname: String, password: String?, mode: String) {
        if (nickname.isBlank()) { _uiState.value = HomeUiState.Error("Nickname cannot be empty"); return }

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

    // ── Join room ─────────────────────────────────────────────────────────

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
                404  -> { _uiState.value = HomeUiState.Error("Room not found. Check the code."); return@launch }
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

    // ── Core flow: connect socket → emit join_room → wait for room_joined ──
    //
    //  IMPORTANT ORDER:
    //  1. Register room_joined / room_error collectors FIRST.
    //     (MutableSharedFlow replay=0 means events emitted before a collector
    //      starts are silently lost. The server responds very fast after
    //      join_room; we cannot risk missing the event.)
    //  2. Call SocketManager.connect()
    //  3. Wait for EVENT_CONNECT (connected.filter{it}.first())
    //  4. Emit join_room
    //  5. The pre-registered collectors will receive room_joined / room_error.

    private suspend fun connectAndJoin(
        roomId:    String,
        nickname:  String,
        password:  String?,
        hostToken: String? = null
    ) {
        // Step 1 — start result collectors BEFORE connecting
        // Using separate child Jobs so we can cancel the loser once one fires.
        var resolved = false

        val joinedJob = viewModelScope.launch {
            val payload = runCatching {
                // Wait up to 30 s for room_joined after connection is established
                withTimeout(30_000L) { SocketManager.roomJoined.first() }
            }.getOrElse { return@launch }

            if (!resolved) {
                resolved = true
                _uiState.value = HomeUiState.Idle
                _navigateToWatch.emit(payload)
            }
        }

        val errorJob = viewModelScope.launch {
            val message = runCatching {
                withTimeout(30_000L) { SocketManager.roomError.first() }
            }.getOrElse { return@launch }

            if (!resolved) {
                resolved = true
                _uiState.value = HomeUiState.Error(message)
                joinedJob.cancel()
            }
        }

        // Step 2 — connect socket
        SocketManager.connect()

        // Step 3 — wait for EVENT_CONNECT
        //  Render free tier can take 30-60 s to wake from sleep → use 60 s timeout.
        val didConnect = runCatching {
            withTimeout(60_000L) {
                SocketManager.connected.filter { it }.first()
            }
        }.isSuccess

        if (!didConnect) {
            resolved = true
            joinedJob.cancel()
            errorJob.cancel()
            _uiState.value = HomeUiState.Error(
                "Could not reach the server.\n" +
                "• Check your internet connection.\n" +
                "• The server may be waking up — wait 30 s and try again."
            )
            return
        }

        // Step 4 — send join_room (collectors from Step 1 are already listening)
        SocketManager.joinRoom(
            JoinRoomPayload(
                roomId    = roomId,
                nickname  = nickname,
                password  = password,
                hostToken = hostToken
            )
        )

        // Step 5 — wait for joinedJob to complete (errorJob races it)
        joinedJob.join()
        errorJob.cancel()

        // If neither collector fired within timeout, give a clear error
        if (!resolved) {
            _uiState.value = HomeUiState.Error("Server connected but did not respond to join request.")
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeJob?.cancel()
    }
}

// ── UI states ─────────────────────────────────────────────────────────────────

sealed class HomeUiState {
    object Idle : HomeUiState()
    object Loading : HomeUiState()
    data class NeedsPassword(val roomId: String) : HomeUiState()
    data class Error(val message: String) : HomeUiState()
}
