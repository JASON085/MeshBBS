package com.meshtastic.bbs.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meshtastic.bbs.data.BbsEvent
import com.meshtastic.bbs.data.Board
import com.meshtastic.bbs.data.FavoriteNodesStore
import com.meshtastic.bbs.data.MeshMessage
import com.meshtastic.bbs.data.MeshNode
import com.meshtastic.bbs.data.MeshtasticRepository
import com.meshtastic.bbs.data.NodeCacheStore
import com.meshtastic.bbs.data.NodeEntry
import com.meshtastic.bbs.data.OnlineUser
import com.meshtastic.bbs.data.Post
import com.meshtastic.bbs.data.PostDetail
import com.meshtastic.bbs.data.UserInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime

sealed class Screen {
    object Login : Screen()
    object ServerSelect : Screen()
    object Boards : Screen()
    data class Posts(val boardName: String) : Screen()
    data class PostView(val postId: Int) : Screen()
    data class Compose(
        val boardName: String,
        val replyToPostId: Int? = null,
        val editPostId: Int? = null,
    ) : Screen()
    object MeshChat : Screen()
}

data class BbsUiState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val myNodeId: String = "",
    val btPermissionGranted: Boolean = true,
    val isLoading: Boolean = false,
    val currentUser: UserInfo? = null,
    val boards: List<Board> = emptyList(),
    val posts: List<Post> = emptyList(),
    val postTotal: Int = 0,
    val currentPage: Int = 1,
    val currentBoardName: String = "",
    val currentPost: PostDetail? = null,
    val meshMessages: List<MeshMessage> = emptyList(),
    val onlineUsers: List<OnlineUser> = emptyList(),
    val meshConnected: Boolean = false,
    val meshNode: String = "",
    val meshNodes: Map<String, MeshNode> = emptyMap(),
    val favoriteServers: List<NodeEntry> = emptyList(),
    val selectedServerId: String = "",
    val selectedServerName: String = "",
    val nodeDebug: String = "",
    val error: String? = null,
    val toast: String? = null,
    val searchResults: List<Post>? = null,
    val searchQuery: String = "",
    val preflightChecked: Boolean = false,
    val preflightRadioConnected: Boolean = false,
    val preflightBbsFound: Boolean = false,
    val bssLog: String = "",
    val diagnosticLog: String = "",
    val pendingIntentCmd: String? = null,
    val submitInProgress: Boolean = false,
    val submitStage: String = "",
    val submitProgress: Int = 0,
    val loadInProgress: Boolean = false,
    val loadStage: String = "",
    val loadProgress: Int = 0,
)

class BbsViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val MAX_BSS_LOG_CHARS = 24_000
        private const val MAX_NODE_DEBUG_LINES = 120
        private const val MAX_MESH_MESSAGES = 200
    }

    private val appCtx = app.applicationContext
    private var repo: MeshtasticRepository? = null
    private var connectJob: Job? = null
    private var postListRetryJob: Job? = null
    private var postReadRetryJob: Job? = null
    private var pendingReplyPostId: Int? = null

    private val _state = MutableStateFlow(BbsUiState())
    val state: StateFlow<BbsUiState> = _state.asStateFlow()

    private val _screen = MutableStateFlow<Screen>(Screen.ServerSelect)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    init {
        val cachedNodes = NodeCacheStore.load(appCtx).associateBy { it.nodeId }
        val favorites = FavoriteNodesStore.load(appCtx)
        _state.update {
            it.copy(
                meshNodes = cachedNodes,
                favoriteServers = favorites,
            )
        }
    }

    fun setBtPermissionGranted(granted: Boolean) {
        _state.update { it.copy(btPermissionGranted = granted) }
    }

    fun connect() {
        if (_state.value.isConnecting) return
        connectJob?.cancel()
        repo?.disconnect()

        val repository = MeshtasticRepository(appCtx)
        repo = repository
        _state.update {
            it.copy(
                isConnecting = true,
                error = null,
                preflightChecked = false,
                preflightRadioConnected = false,
                preflightBbsFound = false,
                bssLog = "",
            )
        }
        appendDiagnostic("SYS", "開始連線 Meshtastic / ${MeshtasticRepository.BUILD}")

        connectJob = viewModelScope.launch {
            repository.connect().collect { event -> handleEvent(event) }
        }
    }

    fun cancelConnect() {
        appendDiagnostic("SYS", "取消連線")
        val current = _state.value
        connectJob?.cancel()
        connectJob = null
        postListRetryJob?.cancel()
        postListRetryJob = null
        repo?.disconnect()
        repo = null
        _state.value = BbsUiState(
            btPermissionGranted = current.btPermissionGranted,
            meshNodes = current.meshNodes,
            favoriteServers = current.favoriteServers,
            diagnosticLog = current.diagnosticLog,
        )
        _screen.value = Screen.ServerSelect
    }

    fun logout() {
        _state.value.currentUser?.let { user ->
            repo?.logout(user.nodeId, user.name)
            viewModelScope.launch {
                delay(450L)
                cancelConnect()
            }
        } ?: cancelConnect()
    }

    fun login(name: String, password: String) {
        _state.update { it.copy(isLoading = true, error = null) }
        repo?.login(name, password)
    }

    fun selectServer(nodeId: String, name: String) {
        if (nodeId.isBlank()) return
        val displayName = name.ifBlank { nodeId }
        repo?.setServerNode(nodeId)
        FavoriteNodesStore.addOrUpdate(appCtx, NodeEntry(nodeId, displayName))
        _state.update {
            it.copy(
                selectedServerId = nodeId,
                selectedServerName = displayName,
                favoriteServers = FavoriteNodesStore.load(appCtx),
                error = null,
            )
        }
        _screen.value = Screen.Login
    }

    fun useBroadcastServer() {
        repo?.setServerNode("")
        _state.update {
            it.copy(
                selectedServerId = "",
                selectedServerName = "廣播",
                error = null,
            )
        }
        _screen.value = Screen.Login
    }

    fun removeFavoriteServer(nodeId: String) {
        FavoriteNodesStore.remove(appCtx, nodeId)
        _state.update {
            it.copy(favoriteServers = FavoriteNodesStore.load(appCtx))
        }
    }

    fun refreshBoards() {
        _state.update { it.copy(isLoading = true) }
        repo?.getBoards()
    }

    fun openBoard(boardName: String) {
        _state.update {
            it.copy(
                currentBoardName = boardName,
                posts = emptyList(),
                postTotal = 0,
                currentPage = 1,
                currentPost = null,
                searchResults = null,
                searchQuery = "",
            )
        }
        _screen.value = Screen.Posts(boardName)
        loadPosts(boardName, 1)
    }

    fun loadPosts(board: String = _state.value.currentBoardName, page: Int = 1) {
        _state.update { it.copy(isLoading = true, currentPage = page) }
        repo?.getPosts(board, page)
        schedulePostListRetry(board, page)
    }

    fun returnToPostList(boardName: String = _state.value.currentBoardName) {
        _screen.value = Screen.Posts(boardName)
        loadPosts(boardName, 1)
    }

    fun loadNextPage() {
        val s = _state.value
        if (s.posts.size < s.postTotal) loadPosts(s.currentBoardName, s.currentPage + 1)
    }

    fun openPost(postId: Int) {
        postReadRetryJob?.cancel()
        _state.update { it.copy(isLoading = true, currentPost = null) }
        _screen.value = Screen.PostView(postId)
        repo?.getPost(postId)
        postReadRetryJob = viewModelScope.launch {
            listOf(5_000L, 9_000L).forEachIndexed { index, waitMs ->
                delay(waitMs)
                val screen = _screen.value
                val state = _state.value
                val stillWaiting = screen is Screen.PostView &&
                    screen.postId == postId &&
                    state.currentPost?.id != postId
                if (!stillWaiting) return@launch
                _state.update { it.copy(isLoading = true) }
                appendDiagnostic("BBS", "文章 $postId 讀取逾時，自動重試 ${index + 1}/2")
                repo?.getPost(postId)
            }
            delay(12_000L)
            val screen = _screen.value
            val state = _state.value
            if (screen is Screen.PostView && screen.postId == postId && state.currentPost?.id != postId) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        toast = "文章讀取逾時，請再試一次",
                    )
                }
            }
        }
    }

    fun togglePush(postId: Int) {
        repo?.togglePush(postId)
    }

    fun changePassword(password: String) {
        _state.update { it.copy(isLoading = true) }
        repo?.changePassword(password)
    }

    fun deletePost(postId: Int) {
        _state.update { it.copy(isLoading = true) }
        repo?.deletePost(postId)
    }

    fun createPost(board: String, title: String, body: String) {
        _state.update { it.copy(isLoading = true) }
        repo?.createPost(board, title, body)
    }

    fun createReply(postId: Int, body: String) {
        _state.update { it.copy(isLoading = true) }
        pendingReplyPostId = postId
        repo?.createReply(postId, body)
    }

    fun editPost(postId: Int, title: String, body: String) {
        _state.update { it.copy(isLoading = true) }
        repo?.editPost(postId, title, body)
    }

    fun editReply(replyId: Int, body: String) {
        _state.update { it.copy(isLoading = true) }
        repo?.editReply(replyId, body)
    }

    fun deleteReply(replyId: Int) {
        _state.update { it.copy(isLoading = true) }
        repo?.deleteReply(replyId)
    }

    fun search(query: String, field: String = "title", board: String? = null) {
        _state.update { it.copy(isLoading = true, searchQuery = query) }
        repo?.searchPosts(query, field, board)
    }

    fun clearSearch() {
        _state.update { it.copy(searchResults = null, searchQuery = "") }
    }

    fun sendMesh(text: String) {
        val user = _state.value.currentUser ?: return
        val time = LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) }
        repo?.sendMesh(text)
        _state.update { s ->
            s.copy(meshMessages = appendLimited(s.meshMessages, MeshMessage(user.nodeId, user.name, text, time)))
        }
    }

    fun navigateTo(screen: Screen) {
        if (screen !is Screen.Posts) {
            postListRetryJob?.cancel()
            postListRetryJob = null
        }
        _screen.value = screen
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearToast() {
        _state.update { it.copy(toast = null) }
    }

    fun clearDiagnosticLog() {
        _state.update { it.copy(diagnosticLog = "") }
    }

    private fun handleEvent(event: BbsEvent) {
        when (event) {
            is BbsEvent.Connected -> {
                _state.update {
                    it.copy(
                        isConnected = true,
                        isConnecting = false,
                        myNodeId = event.myNodeId,
                        meshConnected = true,
                        meshNode = event.myNodeId,
                        meshNodes = it.meshNodes.filterKeys { id -> id != event.myNodeId },
                        preflightChecked = true,
                        preflightRadioConnected = true,
                    )
                }
                NodeCacheStore.merge(appCtx, _state.value.meshNodes.values)
                _screen.value = Screen.ServerSelect
            }

            is BbsEvent.Disconnected -> {
                _state.update {
                    it.copy(
                        isConnected = false,
                        isConnecting = false,
                        isLoading = false,
                        meshConnected = false,
                        toast = "Meshtastic 連線中斷",
                    )
                }
            }

            is BbsEvent.ConnectError -> {
                _state.update {
                    it.copy(
                        isConnecting = false,
                        isLoading = false,
                        error = event.msg,
                        preflightChecked = true,
                    )
                }
            }

            is BbsEvent.LoginOk -> {
                val user = UserInfo(event.nodeId, event.name, event.isAdmin)
                repo?.setCurrentUser(user)
                _state.update {
                    it.copy(
                        isLoading = false,
                        currentUser = user,
                        onlineUsers = event.onlineUsers,
                    )
                }
                _screen.value = Screen.Boards
                refreshBoards()
            }

            is BbsEvent.LoginError -> {
                _state.update { it.copy(isLoading = false, error = event.msg) }
            }

            is BbsEvent.RegisterOk -> {
                _state.update { it.copy(toast = "帳號 ${event.name} 已建立") }
            }

            is BbsEvent.BoardsLoaded -> {
                _state.update {
                    it.copy(
                        isLoading = false,
                        boards = event.boards,
                        onlineUsers = event.onlineUsers,
                        searchResults = null,
                    )
                }
            }

            is BbsEvent.PostsLoaded -> {
                postListRetryJob?.cancel()
                postListRetryJob = null
                _state.update { s ->
                    val merged = if (event.page == 1) event.posts else s.posts + event.posts
                    s.copy(
                        isLoading = false,
                        posts = merged,
                        postTotal = event.total,
                        currentPage = event.page,
                        currentBoardName = event.board,
                    )
                }
            }

            is BbsEvent.PostLoaded -> {
                postReadRetryJob?.cancel()
                postReadRetryJob = null
                _state.update { it.copy(isLoading = false, currentPost = event.detail) }
            }

            is BbsEvent.PostCreated -> {
                _state.update {
                    it.copy(
                        isLoading = false,
                        toast = "發文成功",
                        submitInProgress = false,
                        submitStage = "",
                        submitProgress = 0,
                    )
                }
                val board = _state.value.currentBoardName
                _screen.value = Screen.Posts(board)
                loadPosts(board, 1)
            }

            is BbsEvent.ReplyCreated -> {
                val postId = pendingReplyPostId ?: _state.value.currentPost?.id
                pendingReplyPostId = null
                if (postId != null) {
                    _state.update {
                        it.copy(
                            isLoading = true,
                            currentPost = null,
                            toast = "回覆成功",
                            submitInProgress = false,
                            submitStage = "",
                            submitProgress = 0,
                        )
                    }
                    _screen.value = Screen.PostView(postId)
                    repo?.getPost(postId)
                } else {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            toast = "回覆成功",
                            submitInProgress = false,
                            submitStage = "",
                            submitProgress = 0,
                        )
                    }
                    reloadCurrentPost()
                }
            }

            is BbsEvent.ReplyEdited -> {
                _state.update {
                    it.copy(
                        isLoading = false,
                        toast = "回覆已更新",
                        submitInProgress = false,
                        submitStage = "",
                        submitProgress = 0,
                    )
                }
                reloadCurrentPost()
            }

            is BbsEvent.ReplyDeleted -> {
                _state.update { it.copy(isLoading = false, toast = "回覆已刪除") }
                reloadCurrentPost()
            }

            is BbsEvent.PostEdited -> {
                _state.update {
                    it.copy(
                        isLoading = true,
                        currentPost = null,
                        toast = "已儲存",
                        submitInProgress = false,
                        submitStage = "",
                        submitProgress = 0,
                    )
                }
                _screen.value = Screen.PostView(event.postId)
                repo?.getPost(event.postId)
            }

            is BbsEvent.PostDeleted -> {
                val board = event.board.ifBlank { _state.value.currentBoardName }
                _state.update { it.copy(isLoading = false, toast = "文章已刪除") }
                _screen.value = Screen.Posts(board)
                loadPosts(board, 1)
            }

            is BbsEvent.PushUpdated -> {
                _state.update { s ->
                    s.copy(
                        posts = s.posts.map { post ->
                            if (post.id == event.postId) post.copy(pushCount = event.pushCount, pushed = event.pushed) else post
                        },
                        currentPost = s.currentPost?.let { post ->
                            if (post.id == event.postId) {
                                post.copy(pushCount = event.pushCount, pushed = event.pushed)
                            } else {
                                post
                            }
                        },
                    )
                }
            }

            is BbsEvent.PasswordChanged -> {
                _state.update {
                    it.copy(
                        isLoading = false,
                        toast = "密碼已變更",
                        submitInProgress = false,
                        submitStage = "",
                        submitProgress = 0,
                    )
                }
            }

            is BbsEvent.SubmitProgress -> {
                _state.update {
                    it.copy(
                        submitInProgress = event.active,
                        submitStage = if (event.active) event.stage else "",
                        submitProgress = if (event.active) event.progress.coerceIn(0, 100) else 0,
                    )
                }
            }

            is BbsEvent.LoadProgress -> {
                _state.update {
                    it.copy(
                        loadInProgress = event.active,
                        loadStage = if (event.active) event.stage else "",
                        loadProgress = if (event.active) event.progress.coerceIn(0, 100) else 0,
                    )
                }
            }

            is BbsEvent.SearchResults -> {
                _state.update {
                    it.copy(
                        isLoading = false,
                        searchResults = event.posts,
                        searchQuery = event.query,
                    )
                }
            }

            is BbsEvent.NewMeshMessage -> {
                _state.update { it.copy(meshMessages = appendLimited(it.meshMessages, event.msg)) }
            }

            is BbsEvent.MeshStatus -> {
                _state.update {
                    it.copy(
                        meshConnected = event.connected,
                        meshNode = event.node,
                        toast = if (event.connected) "Mesh 已連線：${event.node}" else "Mesh 裝置斷線",
                    )
                }
            }

            is BbsEvent.UserJoin -> {
                _state.update { s ->
                    s.copy(meshMessages = appendLimited(s.meshMessages, MeshMessage("", "系統", "${event.name} 上線了", "", isSystem = true)))
                }
            }

            is BbsEvent.UserLeave -> {
                _state.update { s ->
                    s.copy(meshMessages = appendLimited(s.meshMessages, MeshMessage("", "系統", "${event.name} 離線了", "", isSystem = true)))
                }
            }

            is BbsEvent.ForceLogout -> {
                cancelConnect()
                _state.update { it.copy(error = event.msg) }
            }

            is BbsEvent.ServerError -> {
                postListRetryJob?.cancel()
                postListRetryJob = null
                _state.update {
                    it.copy(
                        isLoading = false,
                        toast = event.msg,
                        submitInProgress = false,
                        submitStage = "",
                        submitProgress = 0,
                        loadInProgress = false,
                        loadStage = "",
                        loadProgress = 0,
                    )
                }
            }

            is BbsEvent.NewPostNotice -> {
                _state.update { it.copy(toast = "新文章 [${event.board}] ${event.title}") }
            }

            is BbsEvent.PreflightResult -> {
                _state.update {
                    it.copy(
                        preflightChecked = true,
                        preflightRadioConnected = event.radioConnected,
                        preflightBbsFound = event.bbsFound,
                    )
                }
            }

            is BbsEvent.BssCommLog -> {
                _state.update { s ->
                    val log = "${s.bssLog}\n${event.msg}".trimStart('\n')
                    s.copy(bssLog = limitChars(log, MAX_BSS_LOG_CHARS))
                }
                appendDiagnostic("BBS", event.msg)
            }

            is BbsEvent.NodeChanged -> {
                if (event.node.nodeId == _state.value.myNodeId) return
                _state.update { s ->
                    val prev = s.meshNodes[event.node.nodeId]
                    val node = mergeNode(prev, event.node)
                    val nodes = s.meshNodes + (node.nodeId to node)
                    NodeCacheStore.merge(appCtx, nodes.values)
                    s.copy(
                        meshNodes = nodes,
                        favoriteServers = refreshFavoriteNames(s.favoriteServers, node),
                    )
                }
            }

            is BbsEvent.NodeFetchDebug -> {
                _state.update { s ->
                    val debug = listOf(s.nodeDebug, event.info).filter { it.isNotBlank() }.joinToString("\n")
                    s.copy(nodeDebug = limitLines(debug, MAX_NODE_DEBUG_LINES))
                }
                appendDiagnostic("DBG", event.info)
            }

            is BbsEvent.RequestNodeInfo -> Unit
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectJob?.cancel()
        postListRetryJob?.cancel()
        postReadRetryJob?.cancel()
        repo?.disconnect()
    }

    private fun reloadCurrentPost() {
        _state.value.currentPost?.let { repo?.getPost(it.id) }
    }

    private fun schedulePostListRetry(board: String, page: Int) {
        postListRetryJob?.cancel()
        if (page != 1) return
        postListRetryJob = viewModelScope.launch {
            listOf(4_000L, 8_000L).forEachIndexed { index, waitMs ->
                delay(waitMs)
                val screen = _screen.value
                val state = _state.value
                val stillWaiting = screen is Screen.Posts &&
                    screen.boardName == board &&
                    state.currentBoardName == board &&
                    state.posts.isEmpty()
                if (!stillWaiting) return@launch
                _state.update { it.copy(isLoading = true) }
                appendDiagnostic("BBS", "看板 $board 讀取較慢，自動重試 ${index + 1}/2")
                repo?.getPosts(board, page)
            }
            delay(10_000L)
            val screen = _screen.value
            val state = _state.value
            val stillWaiting = screen is Screen.Posts &&
                screen.boardName == board &&
                state.currentBoardName == board &&
                state.posts.isEmpty()
            if (stillWaiting) {
                _state.update { it.copy(isLoading = false, toast = "看板讀取逾時，請重試" ) }
            }
        }
    }

    private fun limitChars(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.takeLast(maxChars)

    private fun limitLines(text: String, maxLines: Int): String =
        text.lines().takeLast(maxLines).joinToString("\n")

    private fun appendLimited(messages: List<MeshMessage>, message: MeshMessage): List<MeshMessage> =
        (messages + message).takeLast(MAX_MESH_MESSAGES)

    private fun mergeNode(prev: MeshNode?, next: MeshNode): MeshNode {
        if (prev == null) return next
        val nextHasName = next.displayName.isNotBlank() && next.displayName != next.nodeId
        val prevHasName = prev.displayName.isNotBlank() && prev.displayName != prev.nodeId
        return when {
            nextHasName -> next.copy(lastSeen = maxOf(prev.lastSeen, next.lastSeen))
            prevHasName -> prev.copy(lastSeen = maxOf(prev.lastSeen, next.lastSeen))
            else -> next.copy(lastSeen = maxOf(prev.lastSeen, next.lastSeen))
        }
    }

    private fun refreshFavoriteNames(favorites: List<NodeEntry>, node: MeshNode): List<NodeEntry> {
        val hasUsefulName = node.displayName.isNotBlank() && node.displayName != node.nodeId
        if (!hasUsefulName) return favorites

        var changed = false
        val updated = favorites.map { entry ->
            if (entry.nodeId == node.nodeId && (entry.name.isBlank() || entry.name == entry.nodeId)) {
                changed = true
                entry.copy(name = node.displayName)
            } else {
                entry
            }
        }
        if (changed) FavoriteNodesStore.addOrUpdate(appCtx, updated.first { it.nodeId == node.nodeId })
        return if (changed) FavoriteNodesStore.load(appCtx) else favorites
    }

    private fun appendDiagnostic(tag: String, message: String) {
        val stamp = LocalTime.now().let { "%02d:%02d:%02d".format(it.hour, it.minute, it.second) }
        _state.update { s ->
            val line = "[$stamp][$tag] $message"
            val merged = listOf(s.diagnosticLog, line).filter { it.isNotBlank() }.joinToString("\n")
            s.copy(diagnosticLog = merged)
        }
    }
}
