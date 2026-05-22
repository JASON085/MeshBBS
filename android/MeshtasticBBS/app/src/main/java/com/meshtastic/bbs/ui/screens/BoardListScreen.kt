package com.meshtastic.bbs.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshtastic.bbs.data.Board
import com.meshtastic.bbs.data.OnlineUser
import com.meshtastic.bbs.ui.theme.AuthorGreen
import com.meshtastic.bbs.ui.theme.Background
import com.meshtastic.bbs.ui.theme.DateGray
import com.meshtastic.bbs.ui.theme.MeshViolet
import com.meshtastic.bbs.ui.theme.OnSurface
import com.meshtastic.bbs.ui.theme.OnSurfaceVariant
import com.meshtastic.bbs.ui.theme.Outline
import com.meshtastic.bbs.ui.theme.Primary
import com.meshtastic.bbs.ui.theme.SurfaceContainer
import com.meshtastic.bbs.ui.theme.Tertiary
import com.meshtastic.bbs.ui.theme.boardGradient
import com.meshtastic.bbs.viewmodel.BbsViewModel
import com.meshtastic.bbs.viewmodel.Screen
import com.meshtastic.bbs.ui.theme.Surface as AppSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardListScreen(vm: BbsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showSearch by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var showChangePassword by remember { mutableStateOf(false) }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }

    val displayed = if (searchText.isBlank()) {
        state.boards
    } else {
        state.boards.filter { it.name.contains(searchText, true) || it.title.contains(searchText, true) }
    }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "MeshBBS",
                            style = MaterialTheme.typography.titleLarge,
                            color = Primary,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            "討論板系統",
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceVariant,
                        )
                    }
                },
                actions = {
                    MeshChip(state.meshConnected, state.meshNode)
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, "搜尋", tint = OnSurfaceVariant)
                    }
                    IconButton(onClick = { vm.refreshBoards() }) {
                        Icon(Icons.Default.Refresh, "重新整理", tint = OnSurfaceVariant)
                    }
                    IconButton(onClick = { vm.logout() }) {
                        Icon(Icons.Default.Logout, "登出", tint = OnSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppSurface),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { vm.navigateTo(Screen.MeshChat) },
                containerColor = MeshViolet,
                contentColor = Color.White,
                shape = CircleShape,
            ) {
                Icon(Icons.Default.WifiTethering, "站內聊天")
            }
        },
    ) { paddingValues ->
        Column(Modifier.padding(paddingValues)) {
            if (showSearch) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = { Text("搜尋看板") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = Primary) },
                    trailingIcon = if (searchText.isNotBlank()) {
                        {
                            IconButton(onClick = { searchText = "" }) {
                                Icon(Icons.Default.Clear, null)
                            }
                        }
                    } else {
                        null
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary,
                        unfocusedBorderColor = Outline,
                    ),
                )
            }

            if (state.onlineUsers.isNotEmpty()) {
                OnlineUsersRow(state.onlineUsers)
            }

            SessionInfoCard(
                account = state.currentUser?.name.orEmpty(),
                onChangePassword = {
                    passwordError = null
                    newPassword = ""
                    confirmPassword = ""
                    showChangePassword = true
                },
            )

            LoadingTransferProgress(
                active = state.loadInProgress,
                stage = state.loadStage,
                progress = state.loadProgress,
            )

            if (state.isLoading && state.boards.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary)
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(displayed, key = { it.name }) { board ->
                    BoardCard(board = board, onClick = { vm.openBoard(board.name) })
                }
                if (displayed.isEmpty() && !state.isLoading) {
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("目前沒有看板", color = OnSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (showChangePassword) {
        AlertDialog(
            onDismissRequest = { showChangePassword = false },
            title = { Text("變更密碼") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = {
                            newPassword = it
                            passwordError = null
                        },
                        label = { Text("新密碼") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = {
                            confirmPassword = it
                            passwordError = null
                        },
                        label = { Text("再次輸入新密碼") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    passwordError?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val valid = Regex("^[A-Za-z0-9]{6,}$").matches(newPassword)
                        passwordError = when {
                            !valid -> "密碼至少 6 碼，限英文或數字"
                            newPassword != confirmPassword -> "兩次輸入的密碼不一致"
                            else -> null
                        }
                        if (passwordError == null) {
                            vm.changePassword(newPassword)
                            showChangePassword = false
                        }
                    },
                ) {
                    Text("變更密碼")
                }
            },
            dismissButton = {
                TextButton(onClick = { showChangePassword = false }) {
                    Text("取消")
                }
            },
            containerColor = AppSurface,
        )
    }

    state.toast?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(3000)
            vm.clearToast()
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = 80.dp, start = 16.dp, end = 16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Snackbar { Text(msg) }
        }
    }
}

@Composable
fun LoadingTransferProgress(active: Boolean, stage: String, progress: Int) {
    if (!active) return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = SurfaceContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stage.ifBlank { "正在接收資料" },
                color = OnSurface,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            LinearProgressIndicator(
                progress = { progress.coerceIn(0, 100) / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = Primary,
            )
            Text(
                "${progress.coerceIn(0, 100)}%",
                color = OnSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun SessionInfoCard(
    account: String,
    onChangePassword: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = SurfaceContainer,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("登入帳號：", color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text(account.ifBlank { "-" }, color = OnSurface, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onChangePassword) {
                Text("變更密碼")
            }
        }
    }
}

@Composable
fun BoardCard(board: Board, onClick: () -> Unit) {
    val gradient = boardGradient(board.name)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(gradient))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0x18000000)))

        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(boardIcon(board.name), null, tint = Color.White, modifier = Modifier.size(24.dp))
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    board.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        board.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xBBFFFFFF),
                    )
                    if (board.moderator != "SYSOP") {
                        Text(
                            " 版主 ${board.moderator}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Tertiary.copy(alpha = 0.9f),
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${board.postCount}",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text("文章", color = Color(0xAAFFFFFF), style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ChevronRight, null, tint = Color(0x88FFFFFF))
        }
    }
}

@Composable
fun OnlineUsersRow(users: List<OnlineUser>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.People, null, tint = AuthorGreen, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text("線上 ${users.size} 人", style = MaterialTheme.typography.labelSmall, color = AuthorGreen)
        Spacer(Modifier.width(8.dp))
        Text(
            users.take(6).joinToString("、") { it.name },
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceVariant,
        )
        if (users.size > 6) {
            Text(" +${users.size - 6}", style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
        }
    }
}

@Composable
fun MeshChip(connected: Boolean, node: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (connected) Color(0x2200E676) else Color(0x22888888))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (connected) AuthorGreen else DateGray),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            if (connected) node.take(10).ifBlank { "Mesh" } else "未連線",
            style = MaterialTheme.typography.labelSmall,
            color = if (connected) AuthorGreen else DateGray,
        )
    }
}

fun boardIcon(name: String): ImageVector = when {
    name.contains("gossip", true) || name.contains("chat", true) -> Icons.Default.Forum
    name.contains("tech", true) || name.contains("dev", true) -> Icons.Default.Code
    name.contains("mesh", true) || name.contains("radio", true) -> Icons.Default.WifiTethering
    name.contains("local", true) || name.contains("area", true) -> Icons.Default.LocationOn
    name.contains("emergency", true) || name.contains("sos", true) -> Icons.Default.Warning
    name.contains("news", true) || name.contains("info", true) -> Icons.Default.Newspaper
    else -> Icons.Default.Dashboard
}
