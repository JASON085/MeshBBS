package com.meshtastic.bbs.server

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PythonServerBridge(private val context: Context) {
    private var storageConfigured = false
    private var cachedModule: PyObject? = null

    private fun module(): PyObject =
        cachedModule ?: python().getModule("meshbbs_android_server").also { cachedModule = it }

    private fun python(): Python {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        return Python.getInstance()
    }

    fun backupDatabase(): String {
        configureStorageIfNeeded()
        val result = JSONObject(
            module().callAttr("backup_database")?.toJava(String::class.java) ?: "{}"
        )
        if (!result.optBoolean("ok", false)) {
            throw IllegalStateException(result.optString("msg", "資料庫備份失敗"))
        }
        return result.optString("path")
    }

    fun adminAction(action: String, payload: JSONObject = JSONObject()) {
        configureStorageIfNeeded()
        val result = JSONObject(
            module().callAttr("admin_action", action, payload.toString())?.toJava(String::class.java) ?: "{}"
        )
        if (!result.optBoolean("ok", false)) {
            throw IllegalStateException(result.optString("msg", "管理操作失敗"))
        }
    }

    fun importDatabaseFromUri(uri: Uri): String {
        configureStorageIfNeeded()
        val tempFile = File(context.cacheDir, "meshbbs-import-${System.currentTimeMillis()}.db")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("無法讀取匯入檔案")

            val result = JSONObject(
                module().callAttr("import_database", tempFile.absolutePath)?.toJava(String::class.java) ?: "{}"
            )
            if (!result.optBoolean("ok", false)) {
                throw IllegalStateException(result.optString("msg", "資料庫匯入失敗"))
            }
            return result.optString("path")
        } finally {
            tempFile.delete()
        }
    }

    fun exportBackupToUri(sourcePath: String, uri: Uri): String {
        configureStorageIfNeeded()
        val sourceFile = File(sourcePath)
        if (!sourceFile.isFile) {
            throw IllegalStateException("找不到備份檔案")
        }
        context.contentResolver.openOutputStream(uri)?.use { output ->
            sourceFile.inputStream().use { input -> input.copyTo(output) }
        } ?: throw IllegalStateException("無法寫入匯出位置")
        return sourceFile.name
    }

    fun deleteBackup(path: String): String {
        configureStorageIfNeeded()
        val sourceFile = File(path)
        if (!sourceFile.isFile) {
            throw IllegalStateException("找不到備份檔案")
        }
        if (!sourceFile.delete()) {
            throw IllegalStateException("刪除備份檔失敗")
        }
        return sourceFile.name
    }

    fun bootstrap(): ServerDashboard = parseDashboard(
        configureStorageIfNeeded().ifBlank {
            module().callAttr("bootstrap")?.toJava(String::class.java) ?: "{}"
        }
    )

    fun refreshDashboard(): ServerDashboard = parseDashboard(
        configureStorageIfNeeded().ifBlank {
            module().callAttr("get_dashboard")?.toJava(String::class.java) ?: "{}"
        }
    )

    fun handleRequest(
        cmd: String,
        args: String,
        nodeId: String,
        nodeName: String,
    ): String? = try {
        configureStorageIfNeeded()
        module().callAttr("handle_request", cmd, args, nodeId, nodeName)?.toJava(String::class.java)
    } catch (e: PyException) {
        JSONObject()
            .put("type", "error")
            .put("msg", "Python server error: ${e.message}")
            .toString()
    }

    private fun configureStorageIfNeeded(): String {
        if (storageConfigured) return ""
        val dbPath = java.io.File(context.filesDir, "bbs.db").absolutePath
        val backupBase = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val backupDir = java.io.File(backupBase, "meshbbs_backups").absolutePath
        storageConfigured = true
        return module().callAttr("configure_storage", dbPath, backupDir)?.toJava(String::class.java) ?: ""
    }

    private fun parseDashboard(json: String): ServerDashboard {
        val obj = JSONObject(json)
        val statsObj = obj.optJSONObject("stats") ?: JSONObject()
        return ServerDashboard(
            stats = ServerStats(
                posts = statsObj.optInt("posts"),
                replies = statsObj.optInt("replies"),
                users = statsObj.optInt("users"),
                online = statsObj.optInt("online"),
                boards = statsObj.optInt("boards"),
                meshMessages = statsObj.optInt("mesh_messages"),
            ),
            boards = obj.optJSONArray("boards").toBoardSummaries(),
            users = obj.optJSONArray("users").toUserSummaries(),
            dbPath = obj.optString("db_path"),
            backupDir = obj.optString("backup_dir"),
            lastBackupPath = obj.optString("last_backup_path"),
            backups = obj.optJSONArray("backups").toBackupEntries(),
            admins = obj.optJSONArray("admins").toAdminSummaries(),
            recentPosts = obj.optJSONArray("recent_posts").toRecentPostSummaries(),
            relayClients = obj.optInt("relay_clients"),
            adminClients = obj.optInt("admin_clients"),
        )
    }

    private fun JSONArray?.toBoardSummaries(): List<ServerBoardSummary> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.let { board ->
                ServerBoardSummary(
                    name = board.optString("name"),
                    title = board.optString("title"),
                    moderator = board.optString("moderator", "SYSOP"),
                    moderatorId = board.optString("moderator_id"),
                    postCount = board.optInt("post_count"),
                )
            }
        }

    private fun JSONArray?.toUserSummaries(): List<ServerUserSummary> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.let { user ->
                ServerUserSummary(
                    nodeId = user.optString("node_id"),
                    name = user.optString("name"),
                    postCount = user.optInt("post_count"),
                    online = user.optBoolean("online"),
                    banned = user.optBoolean("banned"),
                    hasPassword = user.optBoolean("has_password"),
                )
            }
        }

    private fun JSONArray?.toAdminSummaries(): List<ServerAdminSummary> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.let { admin ->
                ServerAdminSummary(
                    id = admin.optInt("id"),
                    username = admin.optString("username"),
                    createdAt = admin.optString("created_at"),
                )
            }
        }

    private fun JSONArray?.toRecentPostSummaries(): List<ServerRecentPostSummary> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.let { post ->
                ServerRecentPostSummary(
                    id = post.optInt("id"),
                    board = post.optString("board"),
                    author = post.optString("author"),
                    title = post.optString("title"),
                    replyCount = post.optInt("reply_count"),
                    pushCount = post.optInt("push_count"),
                    createdAt = post.optString("created_at"),
                )
            }
        }

    private fun JSONArray?.toBackupEntries(): List<ServerBackupEntry> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.let { entry ->
                ServerBackupEntry(
                    name = entry.optString("name"),
                    path = entry.optString("path"),
                    size = entry.optLong("size"),
                    createdAt = entry.optString("created_at"),
                )
            }
        }
}
