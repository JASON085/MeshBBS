package com.meshtastic.bbs.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object ServerHostStore {
    private val _state = MutableStateFlow(ServerHostState())
    val state: StateFlow<ServerHostState> = _state.asStateFlow()

    fun setStarting() {
        _state.update {
            it.copy(
                isStarting = true,
                isRunning = false,
                status = "啟動中",
                error = null,
            )
        }
    }

    fun setRunning(running: Boolean, status: String? = null) {
        _state.update {
            it.copy(
                isStarting = false,
                isRunning = running,
                status = status ?: if (running) "執行中" else "已停止",
            )
        }
    }

    fun setMeshBound(bound: Boolean, myNodeId: String = "") {
        _state.update {
            it.copy(
                meshBound = bound,
                myNodeId = myNodeId.ifBlank { it.myNodeId },
            )
        }
    }

    fun setHopLimit(hopLimit: Int) {
        _state.update { it.copy(hopLimit = hopLimit.coerceIn(1, 7)) }
    }

    fun currentHopLimit(): Int = _state.value.hopLimit

    fun setTransportProfile(profile: TransportProfile) {
        _state.update {
            it.copy(
                transportProfile = profile,
                responseChunkSize = profile.responseChunkSize,
                responseChunkDelayMs = profile.responseChunkDelayMs,
                responseWindowSize = profile.responseWindowSize,
                winAckDebounceMs = profile.winAckDebounceMs,
            )
        }
    }

    fun currentTransportProfile(): TransportProfile = _state.value.transportProfile

    fun setResponseChunkSize(chunkSize: Int) {
        _state.update { it.copy(responseChunkSize = chunkSize.coerceIn(80, 220)) }
    }

    fun currentResponseChunkSize(): Int = _state.value.responseChunkSize

    fun setResponseChunkDelayMs(delayMs: Long) {
        _state.update { it.copy(responseChunkDelayMs = delayMs.coerceIn(100L, 2_000L)) }
    }

    fun currentResponseChunkDelayMs(): Long = _state.value.responseChunkDelayMs

    fun setResponseWindowSize(windowSize: Int) {
        _state.update { it.copy(responseWindowSize = windowSize.coerceIn(1, 4)) }
    }

    fun currentResponseWindowSize(): Int = _state.value.responseWindowSize

    fun setWinAckDebounceMs(debounceMs: Long) {
        _state.update { it.copy(winAckDebounceMs = debounceMs.coerceIn(200L, 5_000L)) }
    }

    fun currentWinAckDebounceMs(): Long = _state.value.winAckDebounceMs

    fun setBroadcastResponsesForDebug(enabled: Boolean) {
        _state.update { it.copy(broadcastResponsesForDebug = enabled) }
    }

    fun currentBroadcastResponsesForDebug(): Boolean = _state.value.broadcastResponsesForDebug

    fun setBroadcastResendForDebug(enabled: Boolean) {
        _state.update { it.copy(broadcastResendForDebug = enabled) }
    }

    fun currentBroadcastResendForDebug(): Boolean = _state.value.broadcastResendForDebug

    fun setResendWindowSize(windowSize: Int) {
        _state.update { it.copy(resendWindowSize = windowSize.coerceIn(1, 2)) }
    }

    fun currentResendWindowSize(): Int = _state.value.resendWindowSize

    fun currentResendTransportProfile(): ResendTransportProfile = _state.value.resendTransportProfile

    fun currentReadTransportProfile(): ReadTransportProfile = _state.value.readTransportProfile

    fun currentPostsTransportProfile(): PostsTransportProfile = _state.value.postsTransportProfile

    fun setDashboard(dashboard: ServerDashboard) {
        _state.update { it.copy(dashboard = dashboard) }
    }

    fun incrementRequest(label: String) {
        _state.update {
            it.copy(
                requestCount = it.requestCount + 1,
                lastEvent = label,
            )
        }
    }

    fun appendLog(line: String) {
        _state.update { it.copy(logs = it.logs + line) }
    }

    fun setError(message: String?) {
        _state.update { it.copy(error = message) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun stop(status: String = "已停止") {
        _state.update {
            it.copy(
                isStarting = false,
                isRunning = false,
                meshBound = false,
                status = status,
            )
        }
    }
}
