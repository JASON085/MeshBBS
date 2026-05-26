package com.meshtastic.bbs.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshtastic.bbs.server.AndroidServerService
import com.meshtastic.bbs.server.PythonServerBridge
import com.meshtastic.bbs.server.ServerAdminSummary
import com.meshtastic.bbs.server.ServerBackupEntry
import com.meshtastic.bbs.server.ServerBoardSummary
import com.meshtastic.bbs.server.ServerHostStore
import com.meshtastic.bbs.server.ServerRecentPostSummary
import com.meshtastic.bbs.server.ServerStats
import com.meshtastic.bbs.server.ServerUserSummary
import com.meshtastic.bbs.server.TransportProfile
import com.meshtastic.bbs.ui.theme.AuthorGreen
import com.meshtastic.bbs.ui.theme.Background
import com.meshtastic.bbs.ui.theme.Error
import com.meshtastic.bbs.ui.theme.MeshViolet
import com.meshtastic.bbs.ui.theme.OnSurface
import com.meshtastic.bbs.ui.theme.OnSurfaceVariant
import com.meshtastic.bbs.ui.theme.Primary
import com.meshtastic.bbs.ui.theme.Surface as AppSurface
import com.meshtastic.bbs.ui.theme.SurfaceContainerHigh
import com.meshtastic.bbs.ui.theme.Tertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.meshtastic.bbs.data.MeshtasticRepository
import org.json.JSONObject
import kotlin.math.roundToInt

private data class BoardEditorState(
    val originalName: String? = null,
    val name: String = "",
    val title: String = "",
    val moderator: String = "SYSOP",
)

private data class AdminEditorState(
    val username: String = "",
    val password: String = "",
)

private data class PasswordEditorState(
    val id: String,
    val title: String,
    val password: String = "",
)

private data class ConfirmState(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val onConfirm: suspend () -> Unit,
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ServerHostScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val state by ServerHostStore.state.collectAsStateWithLifecycle()
    var flashMessage by remember { mutableStateOf<String?>(null) }
    var boardEditor by remember { mutableStateOf<BoardEditorState?>(null) }
    var adminEditor by remember { mutableStateOf<AdminEditorState?>(null) }
    var userPasswordEditor by remember { mutableStateOf<PasswordEditorState?>(null) }
    var adminPasswordEditor by remember { mutableStateOf<PasswordEditorState?>(null) }
    var confirmState by remember { mutableStateOf<ConfirmState?>(null) }
    var pendingBackupExport by remember { mutableStateOf<ServerBackupEntry?>(null) }
    var lockScreenActive by rememberSaveable { mutableStateOf(false) }
    var savedBrightness by remember { mutableStateOf<Float?>(null) }

    fun performAction(logLine: String, action: suspend () -> Unit) {
        scope.launch {
            runCatching { action() }
                .onSuccess {
                    if (logLine.isNotBlank()) {
                        ServerHostStore.appendLog(logLine)
                    }
                    val dashboard = withContext(Dispatchers.IO) {
                        PythonServerBridge(context).refreshDashboard()
                    }
                    ServerHostStore.setDashboard(dashboard)
                }
                .onFailure { flashMessage = it.message ?: "操作失敗" }
        }
    }

    val importDbLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val path = withContext(Dispatchers.IO) {
                        PythonServerBridge(context).importDatabaseFromUri(uri)
                    }
                    ServerHostStore.appendLog("DB 匯入完成：$path")
                    val dashboard = withContext(Dispatchers.IO) {
                        PythonServerBridge(context).refreshDashboard()
                    }
                    ServerHostStore.setDashboard(dashboard)
                }.onFailure {
                    flashMessage = it.message ?: "匯入資料庫失敗"
                }
            }
        }
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val backup = pendingBackupExport
        pendingBackupExport = null
        if (uri != null && backup != null) {
            scope.launch {
                runCatching {
                    val name = withContext(Dispatchers.IO) {
                        PythonServerBridge(context).exportBackupToUri(backup.path, uri)
                    }
                    ServerHostStore.appendLog("DB 備份已匯出：$name")
                }.onFailure {
                    flashMessage = it.message ?: "匯出備份失敗"
                }
            }
        }
    }

    LaunchedEffect(state.isRunning) {
        if (!state.isRunning && lockScreenActive) {
            lockScreenActive = false
        }
    }

    DisposableEffect(activity, lockScreenActive) {
        val window = activity?.window
        if (window != null) {
            if (lockScreenActive) {
                if (savedBrightness == null) {
                    savedBrightness = window.attributes.screenBrightness
                }
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val attrs = window.attributes
                attrs.screenBrightness = 0.03f
                window.attributes = attrs
            } else {
                val attrs = window.attributes
                attrs.screenBrightness = savedBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = attrs
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                savedBrightness = null
            }
        }
        onDispose {
            if (window != null) {
                val attrs = window.attributes
                attrs.screenBrightness = savedBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = attrs
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            savedBrightness = null
        }
    }

    BackHandler(enabled = lockScreenActive) {}

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Android Server 控制台", color = Primary, fontWeight = FontWeight.Black)
                        Text("版本 ${MeshtasticRepository.BUILD}  ${state.status}", color = OnSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.Default.ArrowBack, null, tint = OnSurface)
                    }
                },
                actions = {
                    IconButton(onClick = { AndroidServerService.refresh(context) }) {
                        Icon(Icons.Default.Refresh, null, tint = OnSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppSurface),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StatusStrip(state.isRunning, state.meshBound, state.myNodeId)
                ActionRow(
                    context = context,
                    active = state.isRunning || state.isStarting,
                    hopLimit = state.hopLimit,
                    transportProfile = state.transportProfile,
                    responseChunkSize = state.responseChunkSize,
                    responseChunkDelayMs = state.responseChunkDelayMs,
                    broadcastResponsesForDebug = state.broadcastResponsesForDebug,
                    broadcastResendForDebug = state.broadcastResendForDebug,
                    locked = lockScreenActive,
                    onLock = { lockScreenActive = true },
                    onHopLimitChange = { hopLimit ->
                        if (hopLimit != state.hopLimit) {
                            ServerHostStore.setHopLimit(hopLimit)
                            ServerHostStore.appendLog("Meshtastic hopLimit 已設為 $hopLimit")
                        }
                    },
                    onTransportProfileChange = { profile ->
                        if (profile != state.transportProfile) {
                            ServerHostStore.setTransportProfile(profile)
                            ServerHostStore.appendLog(
                                "Meshtastic transport profile 已設為 ${profile.name} " +
                                    "chunk=${profile.responseChunkSize} delay=${profile.responseChunkDelayMs}ms"
                            )
                        }
                    },
                    onBroadcastResponsesForDebugChange = { enabled ->
                        if (enabled != state.broadcastResponsesForDebug) {
                            ServerHostStore.setBroadcastResponsesForDebug(enabled)
                            ServerHostStore.appendLog(
                                if (enabled) "Meshtastic response 改用 broadcast 測試"
                                else "Meshtastic response 恢復 directed 模式"
                            )
                        }
                    },
                    onBroadcastResendForDebugChange = { enabled ->
                        if (enabled != state.broadcastResendForDebug) {
                            ServerHostStore.setBroadcastResendForDebug(enabled)
                            ServerHostStore.appendLog(
                                if (enabled) "Meshtastic resend 改用 broadcast 測試"
                                else "Meshtastic resend 恢復 directed 模式"
                            )
                        }
                    },
                )
                StatRow(state.dashboard.stats)
                DbCard(
                    dbPath = state.dashboard.dbPath,
                    backupDir = state.dashboard.backupDir,
                    lastBackupPath = state.dashboard.lastBackupPath,
                    backups = state.dashboard.backups,
                    meshMessageCount = state.dashboard.stats.meshMessages,
                    relayClients = state.dashboard.relayClients,
                    adminClients = state.dashboard.adminClients,
                    requestCount = state.requestCount,
                    onBackup = {
                        performAction("DB 備份完成") {
                            val path = withContext(Dispatchers.IO) {
                                PythonServerBridge(context).backupDatabase()
                            }
                            ServerHostStore.appendLog("DB 備份完成：$path")
                        }
                    },
                    onImport = {
                        importDbLauncher.launch(arrayOf("*/*"))
                    },
                    onClearChatCache = {
                        performAction("站內聊天暫存已清除") {
                            withContext(Dispatchers.IO) {
                                PythonServerBridge(context).adminAction("clear_mesh_messages")
                            }
                        }
                    },
                    onExportBackup = { backup ->
                        pendingBackupExport = backup
                        exportBackupLauncher.launch(backup.name)
                    },
                    onDeleteBackup = { backup ->
                        confirmState = ConfirmState(
                            title = "刪除備份檔",
                            message = "確定要刪除 ${backup.name} 嗎？此操作無法復原。",
                            confirmLabel = "刪除",
                        ) {
                            val name = withContext(Dispatchers.IO) {
                                PythonServerBridge(context).deleteBackup(backup.path)
                            }
                            ServerHostStore.appendLog("DB 備份已刪除：$name")
                        }
                    },
                )
                AdminsCard(
                    admins = state.dashboard.admins,
                    onAdd = { adminEditor = AdminEditorState() },
                    onChangePassword = { admin ->
                        adminPasswordEditor = PasswordEditorState(
                            id = admin.id.toString(),
                            title = "變更管理員密碼：${admin.username}",
                        )
                    },
                    onDelete = { admin ->
                        confirmState = ConfirmState(
                            title = "刪除管理員",
                            message = "確定要刪除 ${admin.username} 嗎？",
                            confirmLabel = "刪除",
                        ) {
                            withContext(Dispatchers.IO) {
                                PythonServerBridge(context).adminAction(
                                    "delete_admin",
                                    JSONObject().put("admin_id", admin.id),
                                )
                            }
                        }
                    },
                )
                BoardsCard(
                    boards = state.dashboard.boards,
                    onAdd = { boardEditor = BoardEditorState() },
                    onEdit = { board ->
                        boardEditor = BoardEditorState(
                            originalName = board.name,
                            name = board.name,
                            title = board.title,
                            moderator = board.moderator,
                        )
                    },
                    onDelete = { board ->
                        confirmState = ConfirmState(
                            title = "刪除看板",
                            message = "確定要刪除 ${board.title} 嗎？\n刪除後文章與回覆也會一起移除。",
                            confirmLabel = "刪除",
                        ) {
                            withContext(Dispatchers.IO) {
                                PythonServerBridge(context).adminAction(
                                    "delete_board",
                                    JSONObject().put("name", board.name),
                                )
                            }
                        }
                    },
                )
                UsersCard(
                    users = state.dashboard.users,
                    onToggleBan = { user ->
                        val targetBanned = !user.banned
                        val label = if (targetBanned) "停權" else "解除停權"
                        confirmState = ConfirmState(
                            title = label,
                            message = "確定要將 ${user.name} 設為 $label 嗎？",
                            confirmLabel = label,
                        ) {
                            withContext(Dispatchers.IO) {
                                PythonServerBridge(context).adminAction(
                                    "set_user_ban",
                                    JSONObject()
                                        .put("node_id", user.nodeId)
                                        .put("banned", targetBanned),
                                )
                            }
                        }
                    },
                    onSetPassword = { user ->
                        userPasswordEditor = PasswordEditorState(
                            id = user.nodeId,
                            title = "設定使用者密碼：${user.name}",
                        )
                    },
                    onDeleteUser = { user ->
                        confirmState = ConfirmState(
                            title = "刪除使用者",
                            message = "確定要刪除 ${user.name} 嗎？\n只會刪除使用者帳號資料，不會刪除既有文章與回覆。",
                            confirmLabel = "刪除使用者",
                        ) {
                            withContext(Dispatchers.IO) {
                                PythonServerBridge(context).adminAction(
                                    "delete_user",
                                    JSONObject().put("node_id", user.nodeId),
                                )
                            }
                        }
                    },
                )
                RecentPostsCard(state.dashboard.recentPosts)
                LogsCardFull(state.logs)
                Spacer(Modifier.height(12.dp))
            }

            if (lockScreenActive) {
                ScreenLockOverlay(
                    onUnlock = { lockScreenActive = false },
                )
            }
        }
    }

    boardEditor?.let { editor ->
        BoardEditorDialog(
            state = editor,
            userOptions = listOf("SYSOP") + state.dashboard.users.map { it.name }.filter { it.isNotBlank() }.distinct(),
            onDismiss = { boardEditor = null },
            onConfirm = { updated ->
                boardEditor = null
                val action = if (updated.originalName == null) "create_board" else "update_board"
                performAction("看板已更新：${updated.name}") {
                    withContext(Dispatchers.IO) {
                        PythonServerBridge(context).adminAction(
                            action,
                            JSONObject()
                                .put("name", updated.name.trim())
                                .put("title", updated.title.trim())
                                .put("moderator", updated.moderator.trim()),
                        )
                    }
                }
            },
        )
    }

    adminEditor?.let { editor ->
        AdminEditorDialog(
            state = editor,
            onDismiss = { adminEditor = null },
            onConfirm = { updated ->
                adminEditor = null
                performAction("管理員已新增：${updated.username}") {
                    withContext(Dispatchers.IO) {
                        PythonServerBridge(context).adminAction(
                            "create_admin",
                            JSONObject()
                                .put("username", updated.username.trim())
                                .put("password", updated.password),
                        )
                    }
                }
            },
        )
    }

    userPasswordEditor?.let { editor ->
        PasswordEditorDialog(
            title = editor.title,
            initialValue = editor.password,
            onDismiss = { userPasswordEditor = null },
            onConfirm = { value ->
                userPasswordEditor = null
                performAction("使用者密碼已更新") {
                    withContext(Dispatchers.IO) {
                        PythonServerBridge(context).adminAction(
                            "set_user_password",
                            JSONObject()
                                .put("node_id", editor.id)
                                .put("password", value),
                        )
                    }
                }
            },
        )
    }

    adminPasswordEditor?.let { editor ->
        PasswordEditorDialog(
            title = editor.title,
            initialValue = editor.password,
            onDismiss = { adminPasswordEditor = null },
            onConfirm = { value ->
                adminPasswordEditor = null
                performAction("管理員密碼已更新") {
                    withContext(Dispatchers.IO) {
                        PythonServerBridge(context).adminAction(
                            "change_admin_password",
                            JSONObject()
                                .put("admin_id", editor.id.toInt())
                                .put("password", value),
                        )
                    }
                }
            },
        )
    }

    confirmState?.let { dialog ->
        ConfirmDialog(
            title = dialog.title,
            message = dialog.message,
            confirmLabel = dialog.confirmLabel,
            onDismiss = { confirmState = null },
            onConfirm = {
                confirmState = null
                performAction(dialog.confirmLabel) { dialog.onConfirm() }
            },
        )
    }

    val message = state.error ?: flashMessage
    message?.let {
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(3500)
            if (state.error == message) {
                ServerHostStore.clearError()
            } else if (flashMessage == message) {
                flashMessage = null
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Snackbar { Text(message) }
        }
    }
}

@Composable
private fun StatusStrip(isRunning: Boolean, meshBound: Boolean, myNodeId: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(onClick = {}, label = { Text(if (isRunning) "伺服器執行中" else "伺服器已停止") })
        AssistChip(onClick = {}, label = { Text(if (meshBound) "Meshtastic 已連線" else "等待 Meshtastic") })
        if (myNodeId.isNotBlank()) {
            AssistChip(onClick = {}, label = { Text(myNodeId) })
        }
    }
}

@Composable
private fun ActionRow(
    context: Context,
    active: Boolean,
    hopLimit: Int,
    transportProfile: TransportProfile,
    responseChunkSize: Int,
    responseChunkDelayMs: Long,
    broadcastResponsesForDebug: Boolean,
    broadcastResendForDebug: Boolean,
    locked: Boolean,
    onLock: () -> Unit,
    onHopLimitChange: (Int) -> Unit,
    onTransportProfileChange: (TransportProfile) -> Unit,
    onBroadcastResponsesForDebugChange: (Boolean) -> Unit,
    onBroadcastResendForDebugChange: (Boolean) -> Unit,
) {
    var hopMenuExpanded by remember(hopLimit) { mutableStateOf(false) }
    var profileMenuExpanded by remember(transportProfile) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ElevatedButton(onClick = { AndroidServerService.start(context) }) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.size(8.dp))
                Text(if (active) "執行中" else "啟動")
            }
            ElevatedButton(onClick = { AndroidServerService.stop(context) }) {
                Icon(Icons.Default.Stop, null)
                Spacer(Modifier.size(8.dp))
                Text("停止")
            }
            ElevatedButton(onClick = { requestIgnoreBatteryOptimizations(context) }) {
                Icon(Icons.Default.LockOpen, null)
                Spacer(Modifier.size(8.dp))
                Text("忽略電池優化")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ElevatedButton(
                onClick = onLock,
                enabled = active && !locked,
            ) {
                Icon(Icons.Default.Lock, null)
                Spacer(Modifier.size(8.dp))
                Text(if (locked) "已鎖定" else "鎖屏")
            }
            Box(modifier = Modifier.width(148.dp)) {
                OutlinedTextField(
                    value = hopLimit.toString(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Hop Limit") },
                    trailingIcon = {
                        IconButton(onClick = { hopMenuExpanded = true }) {
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                DropdownMenu(
                    expanded = hopMenuExpanded,
                    onDismissRequest = { hopMenuExpanded = false },
                ) {
                    (1..7).forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.toString()) },
                            onClick = {
                                onHopLimitChange(option)
                                hopMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AssistChip(
                onClick = { onBroadcastResponsesForDebugChange(!broadcastResponsesForDebug) },
                label = {
                    Text(if (broadcastResponsesForDebug) "Response 廣播測試: 開" else "Response 廣播測試: 關")
                },
            )
            AssistChip(
                onClick = { onBroadcastResendForDebugChange(!broadcastResendForDebug) },
                label = {
                    Text(if (broadcastResendForDebug) "Resend 廣播測試: 開" else "Resend 廣播測試: 關")
                },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AssistChip(
                onClick = {},
                label = { Text("Chunk ${responseChunkSize} / ${responseChunkDelayMs}ms") },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(modifier = Modifier.width(220.dp)) {
                OutlinedTextField(
                    value = transportProfile.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Transport Profile") },
                    trailingIcon = {
                        IconButton(onClick = { profileMenuExpanded = true }) {
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                DropdownMenu(
                    expanded = profileMenuExpanded,
                    onDismissRequest = { profileMenuExpanded = false },
                ) {
                    TransportProfile.entries.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text("${profile.name} ${profile.responseChunkSize}/${profile.responseChunkDelayMs}ms") },
                            onClick = {
                                onTransportProfileChange(profile)
                                profileMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenLockOverlay(onUnlock: () -> Unit) {
    var dragOffsetPx by remember { mutableStateOf(0f) }
    val unlockThresholdPx = with(LocalDensity.current) { 140.dp.toPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.88f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("鎖屏中", color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineSmall)
            Text("長按鎖頭後往下滑動即可解鎖", color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodyMedium)
            Surface(
                modifier = Modifier
                    .offset { IntOffset(0, dragOffsetPx.roundToInt()) }
                    .pointerInput(unlockThresholdPx) {
                        detectDragGesturesAfterLongPress(
                            onDragCancel = { dragOffsetPx = 0f },
                            onDragEnd = {
                                if (dragOffsetPx >= unlockThresholdPx) {
                                    onUnlock()
                                }
                                dragOffsetPx = 0f
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            dragOffsetPx = (dragOffsetPx + dragAmount.y).coerceIn(0f, unlockThresholdPx * 1.4f)
                        }
                    },
                shape = RoundedCornerShape(999.dp),
                color = Color.White.copy(alpha = 0.14f),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "鎖屏",
                        tint = Color.White,
                        modifier = Modifier.size(52.dp),
                    )
                    Text("往下滑動解鎖", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun StatRow(stats: ServerStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatTile("看板", stats.boards.toString(), Primary, Modifier.weight(1f))
        StatTile("文章", stats.posts.toString(), Tertiary, Modifier.weight(1f))
        StatTile("回覆", stats.replies.toString(), MeshViolet, Modifier.weight(1f))
        StatTile("線上", stats.online.toString(), AuthorGreen, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = SurfaceContainerHigh,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(value, color = color, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
            Text(label, color = OnSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DbCard(
    dbPath: String,
    backupDir: String,
    lastBackupPath: String,
    backups: List<ServerBackupEntry>,
    meshMessageCount: Int,
    relayClients: Int,
    adminClients: Int,
    requestCount: Int,
    onBackup: () -> Unit,
    onImport: () -> Unit,
    onClearChatCache: () -> Unit,
    onExportBackup: (ServerBackupEntry) -> Unit,
    onDeleteBackup: (ServerBackupEntry) -> Unit,
) {
    SectionCard("資料庫") {
        InfoLine("資料庫路徑", dbPath)
        InfoLine("備份資料夾", backupDir)
        InfoLine("最近備份", lastBackupPath.substringAfterLast('/', lastBackupPath.substringAfterLast('\\')).ifBlank { "-" })
        InfoLine("伺服器聊天記錄", meshMessageCount.toString())
        InfoLine("Relay 客戶端", relayClients.toString())
        InfoLine("管理員客戶端", adminClients.toString())
        InfoLine("請求次數", requestCount.toString())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ElevatedButton(onClick = onBackup) {
                Icon(Icons.Default.Save, null)
                Spacer(Modifier.size(8.dp))
                Text("備份 DB")
            }
            ElevatedButton(onClick = onImport) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.size(8.dp))
                Text("匯入 DB")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ElevatedButton(onClick = onClearChatCache) {
                Icon(Icons.Default.Delete, null)
                Spacer(Modifier.size(8.dp))
                Text("清除伺服器聊天記錄")
            }
        }
        if (backups.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                backups.take(4).forEach { backup ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    )
                    {
                        Text(
                            "${backup.createdAt}  ${backup.name}  ${formatBytes(backup.size)}",
                            color = OnSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onExportBackup(backup) }) {
                            Icon(Icons.Default.Save, null, tint = Primary)
                        }
                        IconButton(onClick = { onDeleteBackup(backup) }) {
                            Icon(Icons.Default.Delete, null, tint = Error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminsCard(
    admins: List<ServerAdminSummary>,
    onAdd: () -> Unit,
    onChangePassword: (ServerAdminSummary) -> Unit,
    onDelete: (ServerAdminSummary) -> Unit,
) {
    SectionCard("管理員") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onAdd) {
                Icon(Icons.Default.PersonAdd, null)
                Spacer(Modifier.size(6.dp))
                Text("新增管理員")
            }
        }
        if (admins.isEmpty()) {
            Text("目前沒有管理員", color = OnSurfaceVariant)
        } else {
            admins.forEach { admin ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(admin.username, color = OnSurface, fontWeight = FontWeight.Bold)
                        Text("建立於：${admin.createdAt}", color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(onClick = { onChangePassword(admin) }) {
                            Icon(Icons.Default.Password, null, tint = Primary)
                        }
                        IconButton(onClick = { onDelete(admin) }) {
                            Icon(Icons.Default.Delete, null, tint = Error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardsCard(
    boards: List<ServerBoardSummary>,
    onAdd: () -> Unit,
    onEdit: (ServerBoardSummary) -> Unit,
    onDelete: (ServerBoardSummary) -> Unit,
) {
    SectionCard("看板") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onAdd) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.size(6.dp))
                Text("新增看板")
            }
        }
        if (boards.isEmpty()) {
            Text("目前沒有看板", color = OnSurfaceVariant)
        } else {
            boards.forEach { board ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(board.title, color = OnSurface, fontWeight = FontWeight.Bold)
                        Text(
                            "${board.name} / ${board.moderator}",
                            color = OnSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(board.postCount.toString(), color = Primary, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            IconButton(onClick = { onEdit(board) }) {
                                Icon(Icons.Default.Edit, null, tint = Primary)
                            }
                            IconButton(onClick = { onDelete(board) }) {
                                Icon(Icons.Default.Delete, null, tint = Error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsersCard(
    users: List<ServerUserSummary>,
    onToggleBan: (ServerUserSummary) -> Unit,
    onSetPassword: (ServerUserSummary) -> Unit,
    onDeleteUser: (ServerUserSummary) -> Unit,
) {
    SectionCard("使用者") {
        if (users.isEmpty()) {
            Text("目前沒有使用者", color = OnSurfaceVariant)
        } else {
            users.forEach { user ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(user.name, color = OnSurface, fontWeight = FontWeight.Bold)
                        Text(user.nodeId, color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        val statusColor = when {
                            user.banned -> Error
                            user.online -> AuthorGreen
                            else -> OnSurfaceVariant
                        }
                        Text(
                            when {
                                user.banned -> "停權"
                                user.online -> "線上"
                                else -> "離線"
                            },
                            color = statusColor,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            IconButton(onClick = { onSetPassword(user) }) {
                                Icon(Icons.Default.Password, null, tint = Primary)
                            }
                            IconButton(onClick = { onToggleBan(user) }) {
                                Icon(
                                    if (user.banned) Icons.Default.LockOpen else Icons.Default.Lock,
                                    null,
                                    tint = if (user.banned) AuthorGreen else Error,
                                )
                            }
                            IconButton(onClick = { onDeleteUser(user) }) {
                                Icon(Icons.Default.Delete, null, tint = Error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentPostsCard(posts: List<ServerRecentPostSummary>) {
    SectionCard("最近文章") {
        if (posts.isEmpty()) {
            Text("目前沒有文章", color = OnSurfaceVariant)
        } else {
            posts.take(8).forEach { post ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(post.title, color = OnSurface, fontWeight = FontWeight.Bold)
                    Text(
                        "${post.board} / ${post.author} / ${post.createdAt}",
                        color = OnSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "推 ${post.pushCount}  回覆 ${post.replyCount}  #${post.id}",
                        color = Primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogsCard(logs: List<String>) {
    SectionCard("摘要紀錄") {
        if (logs.isEmpty()) {
            Text("目前沒有紀錄", color = OnSurfaceVariant)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                logs.takeLast(20).forEach { line ->
                    Text(line, color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun LogsCardFull(logs: List<String>) {
    val context = LocalContext.current
    SectionCard("執行紀錄") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("共 ${logs.size} 筆", color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            TextButton(
                onClick = {
                    val text = logs.joinToString("\n")
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("meshbbs-server-log", text))
                },
                enabled = logs.isNotEmpty(),
            ) {
                Text("複製紀錄")
            }
        }
        if (logs.isEmpty()) {
            Text("目前沒有執行紀錄", color = OnSurfaceVariant)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                logs.forEach { line ->
                    Text(line, color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun BoardEditorDialog(
    state: BoardEditorState,
    userOptions: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (BoardEditorState) -> Unit,
) {
    var name by remember(state) { mutableStateOf(state.name) }
    var title by remember(state) { mutableStateOf(state.title) }
    var moderator by remember(state) { mutableStateOf(state.moderator) }
    var expanded by remember(state) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.originalName == null) "新增看板" else "編輯看板") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("看板名稱") },
                    enabled = state.originalName == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("看板標題") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box {
                    OutlinedTextField(
                        value = moderator,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("版主名稱") },
                        trailingIcon = {
                            IconButton(onClick = { expanded = true }) {
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth(),
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        userOptions.distinct().forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    moderator = option
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        state.copy(
                            name = name,
                            title = title,
                            moderator = moderator,
                        )
                    )
                },
                enabled = name.isNotBlank() && title.isNotBlank() && moderator.isNotBlank(),
            ) { Text("確認") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AdminEditorDialog(
    state: AdminEditorState,
    onDismiss: () -> Unit,
    onConfirm: (AdminEditorState) -> Unit,
) {
    var username by remember(state) { mutableStateOf(state.username) }
    var password by remember(state) { mutableStateOf(state.password) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增管理員") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("帳號") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密碼") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(AdminEditorState(username, password)) },
                enabled = username.isNotBlank() && password.isNotBlank(),
            ) { Text("新增") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PasswordEditorDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember(title, initialValue) { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("使用者密碼") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password) }, enabled = password.isNotBlank()) {
                Text("儲存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceContainerHigh),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = OnSurface, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = OnSurfaceVariant)
        Text(value.ifBlank { "-" }, color = OnSurface, fontWeight = FontWeight.Medium)
    }
}

private fun formatBytes(size: Long): String {
    if (size < 1024) return "${size} B"
    val kb = size / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    return String.format("%.1f MB", kb / 1024.0)
}

private fun requestIgnoreBatteryOptimizations(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    val powerManager = context.getSystemService(PowerManager::class.java)
    if (powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true) return
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(fallback) }
    }
}
