package com.meshtastic.bbs.server

enum class TransportProfile(
    val responseChunkSize: Int,
    val responseChunkDelayMs: Long,
    val responseWindowSize: Int,
    val winAckDebounceMs: Long,
) {
    STABLE(200, 1_200L, 1, 1_000L),
    BALANCED(200, 900L, 2, 800L),
    FAST_TEST(200, 700L, 3, 600L),
    ADAPTIVE(200, 900L, 2, 800L),
}

enum class ResendTransportProfile(
    val resendChunkSize: Int,
    val resendChunkDelayMs: Long,
    val resendBatchSize: Int,
    val resendWindowSize: Int,
) {
    RESEND_SAFE(140, 1_200L, 1, 1),
}

data class ReadTransportProfile(
    val chunkSize: Int = 140,
    val chunkDelayMs: Long = 1_500L,
    val windowSize: Int = 1,
    val winAckDebounceMs: Long = 1_200L,
    val resendWindowSize: Int = 1,
    val headMetaRepeatCount: Int = 2,
    val headMetaIntervalMs: Long = 800L,
    val firstChunkDelayMs: Long = 1_200L,
)

data class PostsTransportProfile(
    val chunkSize: Int = 200,
    val chunkDelayMs: Long = 1_000L,
    val windowSize: Int = 1,
    val winAckDebounceMs: Long = 800L,
    val resendWindowSize: Int = 1,
)

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
    val hopLimit: Int = 4,
    val transportProfile: TransportProfile = TransportProfile.BALANCED,
    val responseChunkSize: Int = TransportProfile.BALANCED.responseChunkSize,
    val responseChunkDelayMs: Long = TransportProfile.BALANCED.responseChunkDelayMs,
    val responseWindowSize: Int = TransportProfile.BALANCED.responseWindowSize,
    val winAckDebounceMs: Long = TransportProfile.BALANCED.winAckDebounceMs,
    val broadcastResponsesForDebug: Boolean = false,
    val resendTransportProfile: ResendTransportProfile = ResendTransportProfile.RESEND_SAFE,
    val resendWindowSize: Int = ResendTransportProfile.RESEND_SAFE.resendWindowSize,
    val readTransportProfile: ReadTransportProfile = ReadTransportProfile(),
    val postsTransportProfile: PostsTransportProfile = PostsTransportProfile(),
    val broadcastResendForDebug: Boolean = false,
    val myNodeId: String = "",
    val status: String = "已停止",
    val requestCount: Int = 0,
    val lastEvent: String = "",
    val dashboard: ServerDashboard = ServerDashboard(),
    val logs: List<String> = emptyList(),
    val error: String? = null,
)
