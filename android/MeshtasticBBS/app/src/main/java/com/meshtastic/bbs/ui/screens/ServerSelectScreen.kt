package com.meshtastic.bbs.ui.screens

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshtastic.bbs.data.MeshNode
import com.meshtastic.bbs.data.MeshtasticRepository
import com.meshtastic.bbs.data.NodeEntry
import com.meshtastic.bbs.ui.theme.AuthorGreen
import com.meshtastic.bbs.ui.theme.Background
import com.meshtastic.bbs.ui.theme.DateGray
import com.meshtastic.bbs.ui.theme.Error
import com.meshtastic.bbs.ui.theme.ErrorContainer
import com.meshtastic.bbs.ui.theme.MeshViolet
import com.meshtastic.bbs.ui.theme.OnErrorContainer
import com.meshtastic.bbs.ui.theme.OnSurfaceVariant
import com.meshtastic.bbs.ui.theme.Outline
import com.meshtastic.bbs.ui.theme.Primary
import com.meshtastic.bbs.ui.theme.Surface
import com.meshtastic.bbs.ui.theme.SurfaceContainer
import com.meshtastic.bbs.ui.theme.SurfaceVariant
import com.meshtastic.bbs.ui.theme.Tertiary
import com.meshtastic.bbs.ui.theme.TertiaryContainer
import com.meshtastic.bbs.viewmodel.BbsViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ServerSelectScreen(vm: BbsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(state.btPermissionGranted, state.isConnected, state.isConnecting) {
        if (state.btPermissionGranted && !state.isConnected && !state.isConnecting) {
            vm.connect()
        }
    }

    var searchText by remember { mutableStateOf("") }
    var favoriteToDelete by remember { mutableStateOf<NodeEntry?>(null) }
    val favoriteIds = state.favoriteServers.map { it.nodeId }.toSet()

    val nodes = state.meshNodes.values
        .sortedWith(
            compareByDescending<MeshNode> { isBbsNode(it.displayName, it.shortName) }
                .thenByDescending { it.lastSeen }
                .thenBy { it.displayName.lowercase() },
        )
    val displayedNodes = if (searchText.isBlank()) {
        nodes
    } else {
        nodes.filter { node ->
            node.displayName.contains(searchText, ignoreCase = true) ||
                node.shortName.contains(searchText, ignoreCase = true) ||
                node.nodeId.contains(searchText, ignoreCase = true)
        }
    }

    if (favoriteToDelete != null) {
        AlertDialog(
            onDismissRequest = { favoriteToDelete = null },
            title = { Text("刪除常用節點") },
            text = { Text("確定要刪除 ${favoriteToDelete?.name ?: ""} 嗎？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        favoriteToDelete?.let { vm.removeFavoriteServer(it.nodeId) }
                        favoriteToDelete = null
                    },
                ) {
                    Text("刪除")
                }
            },
            dismissButton = {
                TextButton(onClick = { favoriteToDelete = null }) {
                    Text("取消")
                }
            },
        )
    }

    Scaffold(containerColor = Background) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF0D1A35), Color(0xFF050810)),
                        center = Offset.Zero,
                        radius = 1600f,
                    ),
                ),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val step = 52.dp.toPx()
                val lineColor = Color(0x0A4B8FFF)
                var x = 0f
                while (x <= size.width) {
                    drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), 0.8f)
                    x += step
                }
                var y = 0f
                while (y <= size.height) {
                    drawLine(lineColor, Offset(0f, y), Offset(size.width, y), 0.8f)
                    y += step
                }
            }

            Box(
                Modifier
                    .size(320.dp)
                    .offset((-70).dp, (-70).dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(MeshViolet.copy(alpha = 0.12f), Color.Transparent))),
            )
            Box(
                Modifier
                    .size(220.dp)
                    .align(Alignment.BottomEnd)
                    .offset(40.dp, 40.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Primary.copy(alpha = 0.1f), Color.Transparent))),
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    HeroHeader(
                        myNodeId = state.myNodeId,
                        version = MeshtasticRepository.BUILD,
                    )
                }

                item {
                    PromptCard(
                        text = "請先透過 Meshtastic APP 連上 LoRa 裝置，再回來使用 MeshBBS",
                    )
                }

                if (!state.btPermissionGranted) {
                    item { BluetoothPermissionCard() }
                } else if (state.isConnecting) {
                    item { StatusCard("正在連接 Meshtastic APP…", true) }
                } else if (!state.isConnected) {
                    item { StatusCard("尚未連上 Meshtastic，請確認 Meshtastic APP 與 LoRa 裝置狀態。") }
                }

                state.error?.let { err ->
                    item { ErrorCard(err) }
                }

                if (state.favoriteServers.isNotEmpty()) {
                    item { SectionLabel("常用節點") }
                    items(state.favoriteServers, key = { "fav-${it.nodeId}" }) { entry ->
                        ServerOptionCard(
                            title = entry.name,
                            subtitle = "最近使用的 BBS 節點",
                            nodeId = entry.nodeId,
                            favorite = true,
                            selected = entry.nodeId == state.selectedServerId,
                            isBbs = isBbsNode(entry.name, ""),
                            icon = { Icon(Icons.Default.Star, contentDescription = null, tint = Tertiary) },
                            onClick = { vm.selectServer(entry.nodeId, entry.name) },
                            onLongClick = { favoriteToDelete = entry },
                        )
                    }
                }

                item { SectionLabel("Meshtastic 節點清單 (${nodes.size})") }
                item {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        label = { Text("搜尋名稱或節點 ID") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (searchText.isNotBlank()) {
                                IconButton(onClick = { searchText = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "清除")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (displayedNodes.isEmpty()) {
                    item { EmptyNodeCard(searchText.isNotBlank(), state.isConnecting, state.isConnected) }
                } else {
                    items(displayedNodes, key = { it.nodeId }) { node ->
                        val isBbs = isBbsNode(node.displayName, node.shortName)
                        ServerOptionCard(
                            title = node.displayName.ifBlank { node.nodeId },
                            subtitle = node.shortName.ifBlank { if (isBbs) "BBS 節點" else "Meshtastic 節點" },
                            nodeId = node.nodeId,
                            favorite = node.nodeId in favoriteIds,
                            selected = node.nodeId == state.selectedServerId,
                            isBbs = isBbs,
                            icon = {
                                Icon(
                                    Icons.Default.Radio,
                                    contentDescription = null,
                                    tint = if (isBbs) Tertiary else AuthorGreen,
                                )
                            },
                            onClick = { vm.selectServer(node.nodeId, node.displayName) },
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun HeroHeader(myNodeId: String, version: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x1AFFFFFF)),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x28FFFFFF)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(MeshViolet.copy(alpha = 0.3f), Primary.copy(alpha = 0.2f))))
                    .border(1.dp, MeshViolet.copy(alpha = 0.4f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.WifiTethering, null, tint = Primary, modifier = Modifier.size(36.dp))
            }

            Spacer(Modifier.height(16.dp))

            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(brush = Brush.linearGradient(listOf(Primary, MeshViolet)))) {
                        append("Mesh")
                    }
                    withStyle(SpanStyle(color = Tertiary)) {
                        append("BBS")
                    }
                },
                fontSize = 34.sp,
                fontWeight = FontWeight.Black,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "版本 $version",
                color = Primary,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = "本機節點 ${myNodeId.ifBlank { "讀取中…" }}",
                color = OnSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PromptCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = TertiaryContainer.copy(alpha = 0.28f)),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Tertiary.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = Tertiary)
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun BluetoothPermissionCard() {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1A00)),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF8C00).copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BluetoothDisabled, null, tint = Color(0xFFFF8C00))
                Spacer(Modifier.width(8.dp))
                Text(
                    "藍牙權限尚未完整開啟，請到 Android 設定中為本 App 開啟相關權限。",
                    color = Color(0xFFFFCC80),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    ).apply {
                        data = android.net.Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF8C00)),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Default.Settings, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("前往 App 設定開啟藍牙權限")
            }
        }
    }
}

@Composable
private fun StatusCard(text: String, loading: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Primary,
                    strokeWidth = 2.dp,
                )
            } else {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Outline),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(text, color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ErrorCard(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ErrorContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = Error)
            Spacer(Modifier.width(10.dp))
            Text(text, color = OnErrorContainer, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = OnSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerOptionCard(
    title: String,
    subtitle: String,
    nodeId: String,
    favorite: Boolean,
    selected: Boolean,
    isBbs: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val cardColor = when {
        selected && isBbs -> Color(0x40FBBF24)
        selected -> Color(0x2636CFC9)
        isBbs -> Color(0x20FBBF24)
        else -> Surface
    }
    val iconBg = if (isBbs) Color(0x26FBBF24) else Color(0x1829B6F6)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Color.White, fontWeight = FontWeight.Bold)
                    if (isBbs) {
                        Spacer(Modifier.width(6.dp))
                        BadgeLabel("BBS", Tertiary)
                    }
                    if (favorite) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = Tertiary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Text(subtitle, color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text(nodeId, color = DateGray, style = MaterialTheme.typography.labelSmall)
            }
            if (selected) {
                Text(
                    "已選擇",
                    color = if (isBbs) Tertiary else Primary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun BadgeLabel(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun EmptyNodeCard(filtered: Boolean, connecting: Boolean, connected: Boolean) {
    val text = when {
        filtered -> "找不到符合搜尋條件的節點。"
        connecting -> "正在等待 Meshtastic 節點資料…"
        connected -> "目前尚未抓到其他節點，請稍等一下。"
        else -> "尚未連上 Meshtastic。"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceContainer),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WifiTethering, contentDescription = null, tint = OnSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                color = OnSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun isBbsNode(displayName: String, shortName: String): Boolean =
    displayName.contains("BBS", ignoreCase = true) || shortName.contains("BBS", ignoreCase = true)
