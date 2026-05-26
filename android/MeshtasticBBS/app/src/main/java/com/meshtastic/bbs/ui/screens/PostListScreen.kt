package com.meshtastic.bbs.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshtastic.bbs.data.Post
import com.meshtastic.bbs.ui.theme.*
import com.meshtastic.bbs.viewmodel.BbsViewModel
import com.meshtastic.bbs.viewmodel.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostListScreen(vm: BbsViewModel, boardName: String) {
    val state     by vm.state.collectAsStateWithLifecycle()
    val listState  = rememberLazyListState()
    var showSearch by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var searchField by remember { mutableStateOf("title") }

    // Infinite scroll: load next page when near bottom
    val shouldLoadMore by remember(
        listState,
        state.posts.size,
        state.postTotal,
        state.isLoading,
        state.searchResults,
    ) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            state.searchResults == null &&
                lastVisible >= state.posts.size - 3 &&
                state.posts.size < state.postTotal &&
                !state.isLoading
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) vm.loadNextPage() }

    val displayedPosts = if (state.searchResults != null) state.searchResults!! else state.posts

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
                    Column {
                        Text(boardName, style = MaterialTheme.typography.titleMedium,
                            color = Primary, fontWeight = FontWeight.Bold)
                        if (state.searchResults != null)
                            Text("搜尋：${state.searchQuery}（${state.searchResults!!.size} 筆）",
                                style = MaterialTheme.typography.labelSmall, color = Secondary)
                        else
                            Text("共 ${state.postTotal} 篇",
                                style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
                    }
                },
                actions = {
                    if (state.searchResults != null) {
                        IconButton({ vm.clearSearch() }) {
                            Icon(Icons.Default.SearchOff, "清除搜尋", tint = Secondary)
                        }
                    } else {
                        IconButton({ showSearch = !showSearch }) {
                            Icon(Icons.Default.Search, "搜尋", tint = OnSurfaceVariant)
                        }
                    }
                    IconButton({ vm.loadPosts(boardName, 1) }) {
                        Icon(Icons.Default.Refresh, "重整", tint = OnSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick          = { vm.navigateTo(Screen.Compose(boardName)) },
                icon             = { Icon(Icons.Default.Edit, null) },
                text             = { Text("發文") },
                containerColor   = Primary,
                contentColor     = OnPrimary,
            )
        }
    ) { pv ->
        Column(Modifier.padding(pv)) {
            LoadingTransferProgress(
                active = state.loadInProgress,
                stage = state.loadStage,
                progress = state.loadProgress,
            )

            // Search bar
            if (showSearch) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    colors   = CardDefaults.cardColors(containerColor = SurfaceContainer),
                    shape    = RoundedCornerShape(14.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            FilterChip("標題", searchField == "title") { searchField = "title" }
                            Spacer(Modifier.width(8.dp))
                            FilterChip("作者", searchField == "author") { searchField = "author" }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value         = searchText,
                            onValueChange = { searchText = it },
                            placeholder   = { Text("輸入關鍵字…") },
                            singleLine    = true,
                            modifier      = Modifier.fillMaxWidth(),
                            shape         = RoundedCornerShape(10.dp),
                            trailingIcon  = {
                                IconButton({ vm.search(searchText, searchField, boardName); showSearch = false }) {
                                    Icon(Icons.Default.Search, null, tint = Primary)
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Primary)
                        )
                    }
                }
            }

            if ((state.isLoading || state.loadInProgress) && displayedPosts.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Primary)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = state.loadStage.ifBlank { "正在接收文章列表" },
                            color = OnSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                return@Column
            }

            LazyColumn(state = listState) {
                itemsIndexed(displayedPosts, key = { _, p -> p.id }) { index, post ->
                    PostRow(
                        post     = post,
                        index    = state.postTotal - (state.currentPage - 1) * 20 - index,
                        onClick  = { vm.openPost(post.id) },
                        onPush   = { vm.togglePush(post.id) }
                    )
                    HorizontalDivider(color = Separator, thickness = 0.5.dp)
                }
                item {
                    if (state.isLoading) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), color = Primary, strokeWidth = 2.dp)
                        }
                    } else if (displayedPosts.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                            Text(
                                "目前沒有文章",
                                color = OnSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    } else if (state.posts.size >= state.postTotal && state.postTotal > 0) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                            Text("已顯示全部 ${state.postTotal} 篇",
                                color = OnSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

// ── Post row ───────────────────────────────────────────────────

@Composable
fun PostRow(post: Post, index: Int, onClick: () -> Unit, onPush: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Post number
        Text(
            text  = index.toString(),
            color = DateGray,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(36.dp),
        )

        // Push badge (clickable)
        PushBadge(post.pushCount, post.pushed, onPush)

        Spacer(Modifier.width(8.dp))

        // Title + meta
        Column(Modifier.weight(1f)) {
            Text(
                text      = post.title,
                color     = if (post.replyCount > 0) Tertiary else OnSurface,
                style     = MaterialTheme.typography.bodyMedium,
                fontWeight= if (post.replyCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                maxLines  = 2,
                overflow  = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(post.author,
                    color = AuthorGreen,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium)
                if (post.replyCount > 0) {
                    Text(" · ${post.replyCount} 則回覆",
                        color = OnSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(Modifier.width(6.dp))
        Text(
            text  = post.createdAt.take(5).replace("/", "-"),
            color = DateGray,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

// ── Push badge ─────────────────────────────────────────────────

@Composable
fun PushBadge(count: Int, pushed: Boolean, onClick: () -> Unit) {
    val (color, text) = when {
        count >= 100 -> PushBoom   to "爆"
        count >= 20  -> PushRed    to count.toString()
        count >= 10  -> PushOrange to count.toString()
        count >= 5   -> PushYellow to count.toString()
        count >= 1   -> PushWhite  to count.toString()
        else         -> PushDim    to ""
    }

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (count > 0) color.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (text.isNotEmpty()) {
            Text(
                text           = text,
                color          = color,
                fontSize       = if (text == "爆") 11.sp else 12.sp,
                fontWeight     = FontWeight.Bold,
                textDecoration = if (pushed) TextDecoration.Underline else TextDecoration.None
            )
        } else {
            Box(Modifier.size(6.dp).clip(CircleShape).background(PushDim))
        }
    }
}

@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick  = onClick,
        label    = { Text(label) },
        colors   = FilterChipDefaults.filterChipColors(
            selectedContainerColor      = PrimaryContainer,
            selectedLabelColor          = OnPrimaryContainer,
        )
    )
}
