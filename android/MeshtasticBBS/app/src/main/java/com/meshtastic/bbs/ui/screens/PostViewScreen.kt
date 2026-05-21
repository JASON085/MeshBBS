package com.meshtastic.bbs.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshtastic.bbs.data.PostDetail
import com.meshtastic.bbs.data.Reply
import com.meshtastic.bbs.ui.theme.*
import com.meshtastic.bbs.viewmodel.BbsViewModel
import com.meshtastic.bbs.viewmodel.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostViewScreen(vm: BbsViewModel, postId: Int) {
    val state by vm.state.collectAsStateWithLifecycle()
    val post = state.currentPost
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDeleteReplyManager by remember { mutableStateOf(false) }
    var editingReply by remember { mutableStateOf<Reply?>(null) }
    var deletingReply by remember { mutableStateOf<Reply?>(null) }

    val currentUser = state.currentUser
    val boardName = post?.board?.ifBlank { state.currentBoardName } ?: state.currentBoardName
    val isMine = postBelongsToUser(post, currentUser)
    val canManage = post != null && isMine
    val myReplies = post?.replies?.filter { replyBelongsToUser(it, currentUser) }.orEmpty()

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton({ vm.returnToPostList(boardName) }) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = Primary)
                    }
                },
                title = {
                    Text(
                        text     = post?.board?.let { "[$it]" } ?: "閱讀文章",
                        style    = MaterialTheme.typography.titleMedium,
                        color    = BoardBlue,
                        maxLines = 1
                    )
                },
                actions = {
                    if (myReplies.isNotEmpty()) {
                        IconButton({ showDeleteReplyManager = true }) {
                            Icon(Icons.Default.Delete, "刪除回覆", tint = Error)
                        }
                    }
                    if (canManage) {
                        IconButton({ vm.navigateTo(Screen.Compose(
                            boardName, editPostId = postId)) }) {
                            Icon(Icons.Default.Edit, "編輯", tint = Tertiary)
                        }
                        IconButton({ showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, "刪除", tint = Error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface)
            )
        },
        floatingActionButton = {
            if (post != null) {
                Column(horizontalAlignment = Alignment.End) {
                    SmallFloatingActionButton(
                        onClick        = { vm.togglePush(postId) },
                        containerColor = if (post.pushed) PushYellow.copy(alpha=0.3f) else SurfaceVariant,
                        contentColor   = PushYellow,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.ThumbUp, "推")
                            if (post.pushCount > 0) {
                                Spacer(Modifier.width(4.dp))
                                Text(post.pushCount.toString(), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FloatingActionButton(
                        onClick        = { vm.navigateTo(Screen.Compose(boardName, replyToPostId = postId)) },
                        containerColor = Secondary,
                        contentColor   = OnSecondary,
                        shape          = CircleShape
                    ) {
                        Icon(Icons.Default.Reply, "回覆")
                    }
                }
            }
        }
    ) { pv ->
        if (state.isLoading && post == null) {
            Box(Modifier.fillMaxSize().padding(pv), Alignment.Center) {
                CircularProgressIndicator(color = Primary)
            }
            return@Scaffold
        }

        if (post == null) {
            Box(Modifier.fillMaxSize().padding(pv), Alignment.Center) {
                Text("找不到文章", color = OnSurfaceVariant)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier       = Modifier.fillMaxSize().padding(pv),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Article header card
            item { ArticleHeader(post) }

            // Article body
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                    shape  = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text     = post.body,
                        modifier = Modifier.padding(16.dp),
                        color    = OnSurface,
                        style    = MaterialTheme.typography.bodyMedium,
                        lineHeight = MaterialTheme.typography.bodyMedium.lineHeight
                    )
                }
            }

            // Replies section header
            if (post.replies.isNotEmpty()) {
                item {
                    Row(
                        Modifier.padding(top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(3.dp, 18.dp).clip(CircleShape).background(Secondary))
                        Spacer(Modifier.width(8.dp))
                        Text("${post.replies.size} 則回覆",
                            style = MaterialTheme.typography.titleSmall, color = Secondary)
                    }
                }
                items(post.replies, key = { it.id }) { reply ->
                    ReplyCard(
                        reply = reply,
                        isAuthorReply = reply.authorId == post.authorId,
                        canManage = replyBelongsToUser(reply, currentUser),
                        onEdit = { editingReply = reply },
                        onDelete = { deletingReply = reply },
                    )
                }
            } else if (!state.isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), Alignment.Center) {
                        Text("目前尚無回覆，成為第一個回覆的人！",
                            color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) } // FAB clearance
        }
    }

    if (showDeleteReplyManager) {
        DeleteReplyManagerDialog(
            replies = myReplies,
            onDismiss = { showDeleteReplyManager = false },
            onDelete = {
                showDeleteReplyManager = false
                deletingReply = it
            },
        )
    }

    // Delete dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("刪除文章", color = Error) },
            text  = { Text("確定刪除這篇文章？此操作無法復原。") },
            confirmButton = {
                TextButton(onClick = { vm.deletePost(postId); showDeleteDialog = false }) {
                    Text("刪除", color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
            containerColor = SurfaceContainer
        )
    }

    editingReply?.let { reply ->
        var body by remember(reply.id) { mutableStateOf(reply.body) }
        AlertDialog(
            onDismissRequest = { editingReply = null },
            title = { Text("修改回覆", color = Secondary) },
            text = {
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("回覆內容") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Secondary)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.editReply(reply.id, body)
                        editingReply = null
                    },
                    enabled = body.isNotBlank() && !state.isLoading
                ) { Text("儲存") }
            },
            dismissButton = {
                TextButton(onClick = { editingReply = null }) { Text("取消") }
            },
            containerColor = SurfaceContainer
        )
    }

    deletingReply?.let { reply ->
        AlertDialog(
            onDismissRequest = { deletingReply = null },
            title = { Text("刪除回覆", color = Error) },
            text = { Text("確定刪除這則回覆？此操作無法復原。") },
            confirmButton = {
                TextButton(onClick = { vm.deleteReply(reply.id); deletingReply = null }) {
                    Text("刪除", color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingReply = null }) { Text("取消") }
            },
            containerColor = SurfaceContainer
        )
    }
}

// ── Article header ─────────────────────────────────────────────

@Composable
fun ArticleHeader(post: PostDetail) {
    val gradient = boardGradient(post.board)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(gradient.map { it.copy(alpha = 0.6f) }))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color(0x33FFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        post.author.take(1).uppercase(),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(post.author, color = AuthorGreen,
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(post.createdAt.take(16), color = Color(0xAAFFFFFF),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(post.title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Reply, null, tint = Color(0xAAFFFFFF), modifier = Modifier.size(13.dp))
                Text(" ${post.replyCount} 則回覆", color = Color(0xAAFFFFFF),
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ── Reply card ─────────────────────────────────────────────────

@Composable
fun ReplyCard(
    reply: Reply,
    isAuthorReply: Boolean,
    canManage: Boolean = false,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        // Author indicator bar
        Box(Modifier.width(3.dp).fillMaxHeight().clip(CircleShape)
            .background(if (isAuthorReply) Secondary else Outline))

        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(reply.author,
                    color = if (isAuthorReply) Secondary else AuthorGreen,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(reply.createdAt.take(16), color = DateGray,
                    style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(3.dp))
            Text(reply.body, color = OnSurface, style = MaterialTheme.typography.bodySmall,
                lineHeight = MaterialTheme.typography.bodySmall.lineHeight)
            if (canManage) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp), tint = Tertiary)
                        Spacer(Modifier.width(4.dp))
                        Text("修改", color = Tertiary, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(14.dp), tint = Error)
                        Spacer(Modifier.width(4.dp))
                        Text("刪除", color = Error, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteReplyManagerDialog(
    replies: List<Reply>,
    onDismiss: () -> Unit,
    onDelete: (Reply) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("刪除回覆", color = Error) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (replies.isEmpty()) {
                    Text("目前沒有可刪除的回覆", color = OnSurfaceVariant)
                } else {
                    replies.forEach { reply ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "#${reply.id}  ${reply.createdAt.take(16)}",
                                color = OnSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(reply.body, color = OnSurface, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onDelete(reply) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                                Icon(Icons.Default.Delete, null, modifier = Modifier.size(14.dp), tint = Error)
                                Spacer(Modifier.width(4.dp))
                                Text("刪除", color = Error, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
        containerColor = SurfaceContainer,
    )
}

private fun replyBelongsToUser(reply: Reply, currentUser: com.meshtastic.bbs.data.UserInfo?): Boolean {
    if (currentUser == null) return false
    return reply.author == currentUser.name
}

private fun postBelongsToUser(post: PostDetail?, currentUser: com.meshtastic.bbs.data.UserInfo?): Boolean {
    if (post == null || currentUser == null) return false
    return post.author == currentUser.name
}
