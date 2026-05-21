package com.meshtastic.bbs.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshtastic.bbs.data.MeshMessage
import com.meshtastic.bbs.ui.theme.*
import com.meshtastic.bbs.viewmodel.BbsViewModel
import com.meshtastic.bbs.viewmodel.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshChatScreen(vm: BbsViewModel) {
    val state    by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var input     by remember { mutableStateOf("") }
    val myId      = state.currentUser?.nodeId ?: ""

    // Auto-scroll to bottom on new messages
    LaunchedEffect(state.meshMessages.size) {
        if (state.meshMessages.isNotEmpty()) {
            runCatching {
                listState.animateScrollToItem(state.meshMessages.lastIndex)
            }
        }
    }

    Scaffold(
        containerColor = Background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton({ vm.navigateTo(Screen.Boards) }) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = Primary)
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape)
                            .background(if (state.meshConnected) AuthorGreen else DateGray))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Mesh 即時通訊", style = MaterialTheme.typography.titleMedium,
                                color = MeshViolet, fontWeight = FontWeight.Bold)
                            Text(
                                if (state.meshConnected) "已連線：${state.meshNode}"
                                else "Mesh 裝置未連線",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.meshConnected) AuthorGreen else DateGray
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface)
            )
        },
        bottomBar = {
            // Input bar
            Surface(color = Surface, shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value         = input,
                        onValueChange = { if (it.length <= 200) input = it },
                        placeholder   = { Text("輸入訊息（最多 200 字）…") },
                        modifier      = Modifier.weight(1f),
                        shape         = RoundedCornerShape(20.dp),
                        maxLines      = 4,
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = MeshViolet,
                            unfocusedBorderColor = Outline,
                        ),
                        trailingIcon  = {
                            Text("${input.length}/200",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (input.length > 180) PushOrange else DateGray,
                                modifier = Modifier.padding(end = 4.dp))
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick  = { if (input.isNotBlank()) { vm.sendMesh(input); input = "" } },
                        enabled  = input.isNotBlank() && state.meshConnected,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(
                                if (input.isNotBlank() && state.meshConnected)
                                    Brush.linearGradient(listOf(MeshViolet, Primary))
                                else Brush.linearGradient(listOf(SurfaceVariant, SurfaceVariant))
                            )
                    ) {
                        Icon(Icons.Default.Send, "送出",
                            tint = if (input.isNotBlank() && state.meshConnected) Color.White else OnSurfaceVariant)
                    }
                }
            }
        }
    ) { pv ->
        if (state.meshMessages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pv), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.WifiTethering, null,
                        tint = MeshViolet.copy(alpha = 0.4f), modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("尚無訊息", color = OnSurfaceVariant)
                    Text("LoRa 廣播訊息會顯示在這裡",
                        color = DateGray, style = MaterialTheme.typography.bodySmall)
                }
            }
            return@Scaffold
        }

        LazyColumn(
            state          = listState,
            modifier       = Modifier.fillMaxSize().padding(pv),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(state.meshMessages) { msg ->
                MeshBubble(msg, isMine = msg.fromId == myId)
            }
        }
    }
}

// ── Chat bubble ────────────────────────────────────────────────

@Composable
fun MeshBubble(msg: MeshMessage, isMine: Boolean) {
    when {
        msg.isSystem -> SystemMessage(msg.text)
        isMine       -> MyBubble(msg)
        else         -> OtherBubble(msg)
    }
}

@Composable
private fun SystemMessage(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            text      = text,
            color     = DateGray,
            fontSize  = 11.sp,
            textAlign = TextAlign.Center,
            modifier  = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 14.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun MyBubble(msg: MeshMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End) {
            Text("我  ${msg.time}", color = DateGray, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 2.dp))
            Box(
                Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp))
                    .background(Brush.linearGradient(listOf(Primary.copy(alpha = 0.8f), MeshViolet.copy(alpha = 0.6f))))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(msg.text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun OtherBubble(msg: MeshMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        // Avatar
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(MeshViolet.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center
        ) {
            Text(msg.from.take(1).uppercase(), color = MeshViolet,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(msg.from, color = MeshViolet,
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Text(msg.time, color = DateGray, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(2.dp))
            Box(
                Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                    .background(SurfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(msg.text, color = OnSurface, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
