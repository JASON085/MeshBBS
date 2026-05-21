package com.meshtastic.bbs.server

data class ServerStats(
    val posts: Int = 0,
    val replies: Int = 0,
    val users: Int = 0,
    val online: Int = 0,
    val boards: Int = 0,
    val meshMessages: Int = 0,
)

data class ServerBoardSummary(
    val name: String,
    val title: String,
    val moderator: String,
    val moderatorId: String,
    val postCount: Int,
)

data class ServerUserSummary(
    val nodeId: String,
    val name: String,
    val postCount: Int,
    val online: Boolean,
    val banned: Boolean,
    val hasPassword: Boolean,
)

data class ServerAdminSummary(
    val id: Int,
    val username: String,
    val createdAt: String,
)

data class ServerRecentPostSummary(
    val id: Int,
    val board: String,
    val author: String,
    val title: String,
    val replyCount: Int,
    val pushCount: Int,
    val createdAt: String,
)

data class ServerBackupEntry(
    val name: String,
    val path: String,
    val size: Long,
    val createdAt: String,
)

data class ServerDashboard(
    val stats: ServerStats = ServerStats(),
    val boards: List<ServerBoardSummary> = emptyList(),
    val users: List<ServerUserSummary> = emptyList(),
    val dbPath: String = "",
    val backupDir: String = "",
    val lastBackupPath: String = "",
    val backups: List<ServerBackupEntry> = emptyList(),
    val admins: List<ServerAdminSummary> = emptyList(),
    val recentPosts: List<ServerRecentPostSummary> = emptyList(),
    val relayClients: Int = 0,
    val adminClients: Int = 0,
)

data class ServerHostState(
    val isRunning: Boolean = false,
    val isStarting: Boolean = false,
    val meshBound: Boolean = false,
    val myNodeId: String = "",
    val status: String = "已停止",
    val requestCount: Int = 0,
    val lastEvent: String = "",
    val dashboard: ServerDashboard = ServerDashboard(),
    val logs: List<String> = emptyList(),
    val error: String? = null,
)
