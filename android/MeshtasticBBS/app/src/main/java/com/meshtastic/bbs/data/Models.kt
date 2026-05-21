package com.meshtastic.bbs.data

import org.json.JSONArray
import org.json.JSONObject

// ── Domain models ──────────────────────────────────────────────

data class UserInfo(val nodeId: String, val name: String, val isAdmin: Boolean = false)

data class Board(
    val name: String,
    val title: String,
    val moderator: String,
    val moderatorId: String,
    val postCount: Int
)

data class Post(
    val id: Int,
    val authorId: String,
    val author: String,
    val title: String,
    val replyCount: Int,
    val pushCount: Int,
    val createdAt: String,
    val pushed: Boolean,
    val board: String = ""
)

data class Reply(
    val id: Int,
    val authorId: String,
    val author: String,
    val body: String,
    val createdAt: String
)

data class PostDetail(
    val id: Int,
    val authorId: String,
    val author: String,
    val title: String,
    val body: String,
    val board: String,
    val replyCount: Int,
    val pushCount: Int,
    val pushed: Boolean,
    val createdAt: String,
    val replies: List<Reply>
)

data class MeshMessage(
    val fromId: String,
    val from: String,
    val text: String,
    val time: String,
    val isSystem: Boolean = false
)

data class OnlineUser(val nodeId: String, val name: String, val postCount: Int)

// ── Meshtastic mesh node (live, from Meshtastic app) ──────────

data class MeshNode(
    val nodeId: String,           // "!a1b2c3d4"
    val displayName: String,
    val shortName: String,
    val lastSeen: Long = 0L,      // System.currentTimeMillis() when last heard via broadcast
)

// ── Saved server node ──────────────────────────────────────────

data class NodeEntry(
    val nodeId: String,
    val name: String,
    val lastUsed: Long = System.currentTimeMillis()
)

// ── Server → Client events ─────────────────────────────────────

sealed class BbsEvent {
    data class Connected(val myNodeId: String) : BbsEvent()
    data class NodeChanged(val node: MeshNode) : BbsEvent()
    data class NodeFetchDebug(val info: String) : BbsEvent()
    object Disconnected : BbsEvent()
    data class ConnectError(val msg: String) : BbsEvent()
    data class LoginOk(val nodeId: String, val name: String, val onlineUsers: List<OnlineUser>, val isAdmin: Boolean = false) : BbsEvent()
    data class LoginError(val msg: String) : BbsEvent()
    data class RegisterOk(val name: String) : BbsEvent()
    data class BoardsLoaded(val boards: List<Board>, val onlineUsers: List<OnlineUser>) : BbsEvent()
    data class PostsLoaded(val board: String, val posts: List<Post>, val total: Int, val page: Int) : BbsEvent()
    data class PostLoaded(val detail: PostDetail) : BbsEvent()
    data class PostCreated(val postId: Int) : BbsEvent()
    data class ReplyCreated(val replyId: Int) : BbsEvent()
    data class ReplyEdited(val replyId: Int) : BbsEvent()
    data class ReplyDeleted(val replyId: Int) : BbsEvent()
    data class PostEdited(val postId: Int) : BbsEvent()
    data class PostDeleted(val postId: Int, val board: String) : BbsEvent()
    data class PushUpdated(val postId: Int, val pushCount: Int, val pushed: Boolean) : BbsEvent()
    data class PasswordChanged(val name: String) : BbsEvent()
    data class SubmitProgress(val stage: String, val progress: Int, val active: Boolean) : BbsEvent()
    data class SearchResults(val posts: List<Post>, val query: String, val total: Int) : BbsEvent()
    data class NewMeshMessage(val msg: MeshMessage) : BbsEvent()
    data class MeshStatus(val connected: Boolean, val node: String) : BbsEvent()
    data class UserJoin(val name: String) : BbsEvent()
    data class UserLeave(val name: String) : BbsEvent()
    data class ForceLogout(val msg: String) : BbsEvent()
    data class ServerError(val msg: String) : BbsEvent()
    data class NewPostNotice(val board: String, val author: String, val title: String) : BbsEvent()
    data class PreflightResult(val radioConnected: Boolean, val bbsFound: Boolean) : BbsEvent()
    // Request the ViewModel to send a targeted NODEINFO request to a specific node
    data class RequestNodeInfo(val nodeId: String) : BbsEvent()
    // BBS protocol communication log (SENT/RES) — kept separate from broadcast noise
    data class BssCommLog(val msg: String) : BbsEvent()
}

// ── JSON parsing helpers ───────────────────────────────────────

private fun JSONArray.stringAt(index: Int, fallback: String = ""): String =
    if (index < length()) optString(index, fallback) else fallback

private fun JSONArray.intAt(index: Int, fallback: Int = 0): Int =
    if (index < length()) optInt(index, fallback) else fallback

fun JSONObject.toBoard() = Board(
    name        = optString("name"),
    title       = optString("title"),
    moderator   = optString("moderator", "SYSOP"),
    moderatorId = optString("moderator_id"),
    postCount   = optInt("post_count")
)

fun JSONArray.toBoardCompact() = Board(
    name        = stringAt(0),
    title       = stringAt(1),
    moderator   = stringAt(3, "SYSOP").ifBlank { "SYSOP" },
    moderatorId = stringAt(4),
    postCount   = intAt(2)
)

fun JSONObject.toPost(board: String = "") = Post(
    id          = optInt("id"),
    authorId    = optString("author_id"),
    author      = optString("author"),
    title       = optString("title"),
    replyCount  = optInt("reply_count"),
    pushCount   = optInt("push_count"),
    createdAt   = optString("created_at"),
    pushed      = optBoolean("pushed"),
    board       = board.ifEmpty { optString("board") }
)

fun JSONArray.toPostCompact(board: String = "") = Post(
    id          = intAt(0),
    authorId    = stringAt(1),
    author      = stringAt(2),
    title       = stringAt(3),
    replyCount  = intAt(4),
    pushCount   = intAt(6),
    createdAt   = stringAt(5),
    pushed      = intAt(7) != 0,
    board       = board
)

fun JSONObject.toReply() = Reply(
    id        = optInt("id"),
    authorId  = optString("author_id"),
    author    = optString("author"),
    body      = optString("body"),
    createdAt = optString("created_at")
)

fun JSONArray.toReplyCompact() = Reply(
    id        = intAt(0),
    authorId  = stringAt(1),
    author    = stringAt(2),
    body      = stringAt(3),
    createdAt = stringAt(4)
)

fun JSONObject.toPostDetail(replies: List<Reply> = emptyList()) = PostDetail(
    id         = optInt("id"),
    authorId   = optString("author_id"),
    author     = optString("author"),
    title      = optString("title"),
    body       = optString("body"),
    board      = optString("board"),
    replyCount = optInt("reply_count", replies.size),
    pushCount  = optInt("push_count"),
    pushed     = optBoolean("pushed"),
    createdAt  = optString("created_at"),
    replies    = replies
)

fun JSONArray.toPostDetailCompact(replies: List<Reply> = emptyList()) = PostDetail(
    id         = intAt(0),
    authorId   = stringAt(1),
    author     = stringAt(2),
    title      = stringAt(3),
    body       = stringAt(4),
    board      = stringAt(6),
    replyCount = replies.size,
    pushCount  = intAt(7),
    pushed     = intAt(8) != 0,
    createdAt  = stringAt(5),
    replies    = replies
)

fun JSONArray.toOnlineUsers() = (0 until length()).map {
    getJSONObject(it).run {
        OnlineUser(optString("node_id"), optString("name"), optInt("post_count"))
    }
}

fun JSONArray.toBoards() = (0 until length()).mapNotNull {
    when (val item = opt(it)) {
        is JSONObject -> item.toBoard()
        is JSONArray  -> item.toBoardCompact()
        else          -> null
    }
}.filter { it.name.isNotBlank() }

fun JSONArray.toPosts(board: String = "") = (0 until length()).mapNotNull {
    when (val item = opt(it)) {
        is JSONObject -> item.toPost(board)
        is JSONArray  -> item.toPostCompact(board)
        else          -> null
    }
}.filter { it.id != 0 }

fun JSONArray.toReplies() = (0 until length()).mapNotNull {
    when (val item = opt(it)) {
        is JSONObject -> item.toReply()
        is JSONArray  -> item.toReplyCompact()
        else          -> null
    }
}
