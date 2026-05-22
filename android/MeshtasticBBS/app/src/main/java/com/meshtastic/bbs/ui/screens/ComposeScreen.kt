package com.meshtastic.bbs.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshtastic.bbs.ui.theme.AuthorGreen
import com.meshtastic.bbs.ui.theme.Background
import com.meshtastic.bbs.ui.theme.DateGray
import com.meshtastic.bbs.ui.theme.MeshViolet
import com.meshtastic.bbs.ui.theme.OnSurface
import com.meshtastic.bbs.ui.theme.OnSurfaceVariant
import com.meshtastic.bbs.ui.theme.Outline
import com.meshtastic.bbs.ui.theme.Primary
import com.meshtastic.bbs.ui.theme.Secondary
import com.meshtastic.bbs.ui.theme.Surface
import com.meshtastic.bbs.ui.theme.SurfaceContainer
import com.meshtastic.bbs.ui.theme.SurfaceVariant
import com.meshtastic.bbs.ui.theme.Tertiary
import com.meshtastic.bbs.viewmodel.BbsViewModel
import com.meshtastic.bbs.viewmodel.Screen

private val POST_TYPES = listOf("公告", "閒聊", "技術", "求助", "交易")
private val TYPE_COLORS = listOf(Primary, Secondary, Tertiary, AuthorGreen, MeshViolet)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    vm: BbsViewModel,
    boardName: String,
    replyToPostId: Int? = null,
    editPostId: Int? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val isReply = replyToPostId != null
    val isEdit = editPostId != null
    val submitLocked = state.submitInProgress || state.isLoading

    val context = LocalContext.current
    DisposableEffect(submitLocked) {
        val window = (context as? Activity)?.window
        if (submitLocked) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    var selectedType by rememberSaveable(isReply, isEdit, boardName, replyToPostId, editPostId) {
        mutableStateOf(if (isReply || isEdit) -1 else 0)
    }
    var title by rememberSaveable(isEdit, editPostId) {
        mutableStateOf(if (isEdit) extractEditTitle(state.currentPost?.title.orEmpty()) else "")
    }
    var body by rememberSaveable(isEdit, isReply, replyToPostId, editPostId) {
        mutableStateOf(if (isEdit) state.currentPost?.body ?: "" else "")
    }

    val screenLabel = when {
        isEdit -> "編輯文章"
        isReply -> "回覆文章"
        else -> "[$boardName] 發表文章"
    }
    val canSubmit = when {
        isReply -> body.isNotBlank()
        else -> title.isNotBlank() && body.isNotBlank()
    } && !submitLocked

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = {
                            vm.navigateTo(
                                if (isReply || isEdit) {
                                    Screen.PostView(replyToPostId ?: editPostId ?: 0)
                                } else {
                                    Screen.Posts(boardName)
                                }
                            )
                        },
                        enabled = !submitLocked,
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "關閉", tint = OnSurfaceVariant)
                    }
                },
                title = {
                    Text(screenLabel, style = MaterialTheme.typography.titleMedium, color = OnSurface)
                },
                actions = {
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (canSubmit) {
                                    Brush.horizontalGradient(listOf(Primary, Secondary))
                                } else {
                                    Brush.horizontalGradient(listOf(SurfaceVariant, SurfaceVariant))
                                }
                            )
                            .clickable(enabled = canSubmit) {
                                when {
                                    isEdit -> vm.editPost(editPostId!!, buildTitle(selectedType, title), body)
                                    isReply -> vm.createReply(replyToPostId!!, body)
                                    else -> vm.createPost(boardName, buildTitle(selectedType, title), body)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        if (submitLocked) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("發送", color = if (canSubmit) Color.White else OnSurfaceVariant)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface),
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (submitLocked) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            state.submitStage.ifBlank { "正在處理文章" },
                            color = OnSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        LinearProgressIndicator(
                            progress = { state.submitProgress.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${state.submitProgress.coerceIn(0, 100)}%",
                            color = OnSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (!isReply && !isEdit) {
                Text("文章類型", style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    POST_TYPES.forEachIndexed { index, label ->
                        val color = TYPE_COLORS[index]
                        val selected = selectedType == index
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (selected) color.copy(alpha = 0.25f) else SurfaceVariant)
                                .clickable(enabled = !submitLocked) { selectedType = index },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                color = if (selected) color else OnSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

            if (!isReply) {
                val prefix = if (selectedType >= 0) "[${POST_TYPES[selectedType]}] " else ""
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("標題") },
                    prefix = if (prefix.isNotEmpty()) {
                        { Text(prefix, color = TYPE_COLORS.getOrElse(selectedType) { Primary }) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !submitLocked,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Outline,
                        focusedLabelColor = Primary,
                    ),
                )
            }

            if (isReply) {
                state.currentPost?.let { post ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("回覆文章", style = MaterialTheme.typography.labelSmall, color = Secondary)
                            Text(post.title, style = MaterialTheme.typography.bodySmall, color = OnSurface)
                            Text("作者：${post.author}", style = MaterialTheme.typography.labelSmall, color = AuthorGreen)
                        }
                    }
                }
            }

            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text(if (isReply) "回覆內容" else "文章內容") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 420.dp),
                enabled = !submitLocked,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = if (isReply) Secondary else Primary,
                    unfocusedBorderColor = Outline,
                    focusedLabelColor = if (isReply) Secondary else Primary,
                ),
                placeholder = { Text("輸入內容...", color = OnSurfaceVariant.copy(alpha = 0.6f)) },
            )

            Text(
                "${body.length} 字",
                color = DateGray,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

private fun buildTitle(typeIndex: Int, title: String): String {
    val prefix = if (typeIndex >= 0) "[${POST_TYPES[typeIndex]}] " else ""
    return "$prefix$title"
}

private fun extractEditTitle(title: String): String {
    val prefix = POST_TYPES.firstOrNull { title.startsWith("[$it] ") }?.let { "[$it] " } ?: ""
    return title.removePrefix(prefix)
}
