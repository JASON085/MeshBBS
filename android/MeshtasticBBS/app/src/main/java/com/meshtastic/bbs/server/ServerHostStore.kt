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
