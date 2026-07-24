package top.mvpdark.lx.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import top.mvpdark.lx.core.util.EncodeUtils
import top.mvpdark.lx.core.util.ImageSaver
import top.mvpdark.lx.core.util.UrlResolver
import top.mvpdark.lx.core.util.formatMessageTime
import top.mvpdark.lx.core.util.formatSessionTime
import top.mvpdark.lx.data.model.ChatMessage
import top.mvpdark.lx.ui.chat.ChatViewModel
import top.mvpdark.lx.ui.components.ChatBubble
import top.mvpdark.lx.ui.components.FullScreenImagePreview
import top.mvpdark.lx.ui.components.UploadThumbnail
import top.mvpdark.lx.ui.emoji.AnimatedEmoji
import top.mvpdark.lx.ui.emoji.EmojiState
import top.mvpdark.lx.ui.imageedit.rememberImagePickerLauncher
import top.mvpdark.lx.ui.theme.Champagne
import top.mvpdark.lx.ui.theme.ChampagneBright
import top.mvpdark.lx.ui.theme.ChampagneDeep
import top.mvpdark.lx.ui.theme.GoldDivider
import top.mvpdark.lx.ui.theme.LxThemeStyle
import top.mvpdark.lx.ui.theme.LocalThemeStyle
import top.mvpdark.lx.ui.theme.Obsidian
import top.mvpdark.lx.ui.theme.ObsidianSurface

/**
 * 聊天页面（核心页面）。
 *
 * - 会话列表侧边栏（可展开/收起）
 * - 消息列表（LazyColumn）：AI 气泡（左）+ 用户气泡（右）
 * - Agent 状态卡片（显示 routing/dispatch/agent_done 等）
 * - 底部输入栏：文本输入框 + 发送按钮 + 图片上传按钮（暂用图标占位）
 * - 流式消息实时显示（delta 追加）
 *
 * @param sessionId 当前会话 ID（由导航参数传入）。
 * @param chatViewModel 聊天 ViewModel（Koin 注入）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    chatViewModel: ChatViewModel = koinViewModel(),
) {
    val state by chatViewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val isNoirAurum = LocalThemeStyle.current == LxThemeStyle.NOIR_AURUM

    // 图片保存：ImageSaver 由 Koin 注入，保存结果通过 Snackbar 反馈
    val imageSaver: ImageSaver = koinInject()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val saveImageToDevice: (String) -> Unit = { url ->
        scope.launch {
            val result = imageSaver.saveImage(
                imageUrl = UrlResolver.resolveImageUrl(url),
                suggestedName = "lx_chat",
            )
            snackbarHostState.showSnackbar(
                result.getOrElse { it.message ?: "保存失败" },
            )
        }
    }

    // 待发送图片（本地字节流）+ 全屏预览开关
    var pickedImageBytes by remember { mutableStateOf<ByteArray?>(null) }
    var showImagePreview by remember { mutableStateOf(false) }
    // 消息图片全屏预览 URL（点击聊天气泡中的图片时设置）
    var previewImageUrl by remember { mutableStateOf<String?>(null) }

    // 跨平台图片选择器：选好图片后回调字节流
    val launchImagePicker = rememberImagePickerLauncher { bytes ->
        if (bytes != null) {
            pickedImageBytes = bytes
        }
    }

    // 进入时选中会话并加载历史消息
    LaunchedEffect(sessionId) {
        chatViewModel.selectSession(sessionId)
    }

    // 用户是否接近底部（derivedStateOf 避免频繁重组）
    val isNearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = info.totalItemsCount
            total <= 0 || (total - 1 - lastVisible) <= 1
        }
    }

    // 自动滚动：仅在用户接近底部时触发，snapshotFlow 节流避免频繁滚动（方案 B）
    LaunchedEffect(listState) {
        snapshotFlow { state.messages.size to state.streamingText.isNotEmpty() }
            .distinctUntilChanged()
            .collect {
                if (isNearBottom) {
                    val lastIndex = state.messages.size + if (state.streamingText.isNotEmpty()) 1 else 0
                    if (lastIndex > 0) {
                        listState.animateScrollToItem(lastIndex - 1)
                    }
                }
            }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "团团",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (isNoirAurum) FontWeight.ExtraLight else FontWeight.SemiBold,
                                color = if (isNoirAurum) ChampagneBright else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (state.isSending) "正在回复..." else "猫娘 AI 助手",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isNoirAurum) ChampagneDeep else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = chatViewModel::toggleSessionsPanel) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = "会话列表",
                                tint = if (isNoirAurum) Champagne else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                    ),
                )
                // Noir Aurum：顶栏底部金色分割线
                if (isNoirAurum) {
                    HorizontalDivider(color = GoldDivider, thickness = 1.dp)
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // 会话列表侧边栏
                AnimatedVisibility(visible = state.isSessionsPanelOpen) {
                    SessionSidebar(
                        sessions = state.sessions,
                        currentSessionId = state.currentSessionId,
                        onSelect = chatViewModel::selectSession,
                        onCreateNew = {
                            chatViewModel.createNewSession { id -> }
                        },
                        onTogglePin = chatViewModel::togglePin,
                        onDelete = chatViewModel::deleteSession,
                        modifier = Modifier
                            .fillMaxHeight()
                            .widthIn(max = 260.dp)
                            .width(260.dp),
                    )
                }

                // 主聊天区
                Column(modifier = Modifier.fillMaxSize()) {
                    // 消息列表
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.messages.isEmpty() && state.streamingText.isEmpty()) {
                            item {
                                EmptyChatHint()
                            }
                        }

                        // 历史与用户消息
                        // 兜底 key：后端历史消息若未返回 id（空串），用
                        // timestamp + content 前缀生成唯一 key，避免
                        // LazyColumn 重复 key 崩溃。
                        items(
                            state.messages,
                            key = { msg ->
                                msg.id.ifBlank {
                                    "msg_${msg.timestamp}_${msg.content.take(10)}"
                                }
                            },
                        ) { message ->
                            ChatBubble(
                                text = message.content,
                                isUser = message.role == "user",
                                images = message.images,
                                timestamp = formatMessageTime(message.timestamp),
                                onImageClick = { url -> previewImageUrl = url },
                                onImageLongClick = saveImageToDevice,
                                onImageSave = saveImageToDevice,
                            )
                        }

                        // Agent 状态卡片（团团表情 + 状态文字）
                        if (state.agentStatus != null && state.streamingText.isEmpty()) {
                            item {
                                AgentStatusCard(
                                    status = state.agentStatus!!,
                                    events = state.agentEvents,
                                    emojiState = state.emojiState,
                                    agentEmojiPath = state.agentEmojiPath,
                                )
                            }
                        }

                        // 流式回复气泡（实时追加 + 团团表情 + 流式期间收到的图片）
                        if (state.streamingText.isNotEmpty() || state.pendingImages.isNotEmpty()) {
                            item {
                                Column {
                                    ChatBubble(
                                        text = state.streamingText,
                                        isUser = false,
                                        images = state.pendingImages,
                                        onImageClick = { url -> previewImageUrl = url },
                                        onImageLongClick = saveImageToDevice,
                                        onImageSave = saveImageToDevice,
                                    )
                                    if (state.isSending) {
                                        TypingIndicator()
                                    }
                                }
                            }
                        }

                        // 发送中但还没开始流式回复：显示团团表情
                        if (state.isSending && state.streamingText.isEmpty() && state.agentStatus == null) {
                            item {
                                EmojiDisplayRow(emojiState = state.emojiState)
                            }
                        }
                    }

                    HorizontalDivider(
                        color = if (isNoirAurum) GoldDivider else MaterialTheme.colorScheme.outlineVariant,
                        thickness = 1.dp,
                    )

                    // 教学模式开关
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = state.teachingMode,
                            onClick = chatViewModel::toggleTeachingMode,
                            label = { Text("教学模式") },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.School,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }

                    // 底部输入栏
                    ChatInputBar(
                        text = state.inputText,
                        onTextChange = chatViewModel::onInputTextChange,
                        onSend = {
                            val bytes = pickedImageBytes
                            if (bytes != null) {
                                chatViewModel.sendMessage(
                                    imageUrl = EncodeUtils.bytesToDataUrl(bytes),
                                )
                                pickedImageBytes = null
                            } else {
                                chatViewModel.sendMessage()
                            }
                        },
                        isSending = state.isSending,
                        pickedImageBytes = pickedImageBytes,
                        onPickImage = launchImagePicker,
                        onPreviewImage = { showImagePreview = true },
                        onRemoveImage = { pickedImageBytes = null },
                    )
                }
            }
        }
    }

    // 全屏图片预览覆盖层 — 放在 Scaffold 外层，真正全屏无 padding 约束
    // 横屏时自动隐藏系统栏（沉浸式），竖屏保持系统栏可见
    if (showImagePreview && pickedImageBytes != null) {
        FullScreenImagePreview(
            model = pickedImageBytes,
            onDismiss = { showImagePreview = false },
        )
    }
    previewImageUrl?.let { url ->
        FullScreenImagePreview(
            model = UrlResolver.resolveImageUrl(url),
            onDismiss = { previewImageUrl = null },
            onSave = { saveImageToDevice(url) },
        )
    }
}

/**
 * 会话侧边栏。
 *
 * 特性：
 * - 会话项支持双向滑动：左滑固定/取消固定，右滑删除
 * - 置顶会话显示图钉图标，排在列表顶部
 * - 长按会话项弹出删除确认对话框
 */
@Composable
private fun SessionSidebar(
    sessions: List<top.mvpdark.lx.data.model.ChatSession>,
    currentSessionId: String,
    onSelect: (String) -> Unit,
    onCreateNew: () -> Unit,
    onTogglePin: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 删除确认对话框状态
    var deleteTarget by remember { mutableStateOf<top.mvpdark.lx.data.model.ChatSession?>(null) }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "会话列表",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onCreateNew) {
                    Icon(Icons.Default.Add, contentDescription = "新建会话")
                }
            }
            HorizontalDivider()

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SwipeableSessionItem(
                        session = session,
                        isSelected = session.id == currentSessionId,
                        onClick = { onSelect(session.id) },
                        onTogglePin = { onTogglePin(session.id) },
                        onDelete = { deleteTarget = session },
                    )
                }
            }
        }
    }

    // 删除确认对话框
    deleteTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话") },
            text = { Text("确定要删除「${session.title}」吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(session.id)
                    deleteTarget = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            },
        )
    }
}

/**
 * 可滑动的会话项。
 *
 * 交互：
 * - 左滑（向左拖动）：露出右侧"固定/取消固定"按钮
 * - 右滑（向右拖动）：露出左侧"删除"按钮
 * - 点击会话项主体：切换会话
 * - 释放时根据偏移量决定操作或回弹
 */
@Composable
private fun SwipeableSessionItem(
    session: top.mvpdark.lx.data.model.ChatSession,
    isSelected: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    val maxSwipe = 80.dp
    val maxSwipePx = with(androidx.compose.ui.platform.LocalDensity.current) { maxSwipe.toPx() }
    val threshold = maxSwipePx * 0.5f

    var offsetX by remember(session.id) { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(56.dp),
    ) {
        // 背景操作按钮层
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧：删除按钮（右滑时露出）
            if (offsetX > 0) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.error)
                        .clickable { onDelete() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }

            // 右侧：固定/取消固定按钮（左滑时露出）
            if (offsetX < 0) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { onTogglePin() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = if (session.pinned) "取消固定" else "固定",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }

        // 前景：会话项主体
        Row(
            modifier = Modifier
                .fillMaxSize()
                .absoluteOffset { IntOffset(offsetX.roundToInt(), 0) }
                .background(
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    } else {
                        Color.Transparent
                    }
                )
                .clickable {
                    // 如果处于滑动状态，先复位
                    if (offsetX != 0f) {
                        offsetX = 0f
                    } else {
                        onClick()
                    }
                }
                .pointerInput(session.id) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            // 释放时根据偏移量决定操作
                            when {
                                offsetX <= -threshold -> {
                                    // 左滑超过阈值：触发固定
                                    onTogglePin()
                                    offsetX = 0f
                                }
                                offsetX >= threshold -> {
                                    // 右滑超过阈值：触发删除
                                    onDelete()
                                    offsetX = 0f
                                }
                                else -> {
                                    // 未超过阈值：回弹
                                    offsetX = 0f
                                }
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            offsetX = 0f
                        },
                    ) { _, dragAmount ->
                        // 累加偏移量，限制在 [-maxSwipePx, maxSwipePx]
                        offsetX = (offsetX + dragAmount).coerceIn(-maxSwipePx, maxSwipePx)
                    }
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 置顶图标（已固定时显示）
            if (session.pinned) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = "已固定",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
                if (session.updatedAt.isNotBlank()) {
                    Text(
                        text = formatSessionTime(session.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Agent 状态信息（用于 AgentStatusCard 内部解析各 Agent 工作状态）。
 */
private data class AgentStatusInfo(
    val name: String,
    val key: String,
    val state: String, // "working" | "done" | "error"
)

/**
 * Agent 状态卡片：显示团团表情 + 各 Agent 独立工作状态。
 *
 * 解析 agentEvents 列表，为每个被派发的 Agent 显示独立的状态行：
 * - 工作中（已 dispatch 但未 done/error）
 * - 已完成（agent_done）
 * - 出错（agent_error）
 */
@Composable
private fun AgentStatusCard(
    status: String,
    events: List<top.mvpdark.lx.data.model.AgentEvent>,
    emojiState: EmojiState = EmojiState.THINKING,
    agentEmojiPath: String? = null,
) {
    val isNoirAurum = LocalThemeStyle.current == LxThemeStyle.NOIR_AURUM
    val cardShape = RoundedCornerShape(16.dp)

    // 从事件列表中解析各 Agent 状态
    // 1. 找到 dispatch 事件获取被派发的 agent 列表
    val dispatchEvent = events.firstOrNull { it.type == "dispatch" }
    val dispatchedAgents = dispatchEvent?.agentsDispatched ?: emptyList()

    // 2. 为每个 agent 判断当前状态
    val agentStatusList = dispatchedAgents.map { agentName ->
        val key = when {
            agentName.contains("构图") -> "composition_advisor"
            agentName.contains("调色") -> "color_grader"
            agentName.contains("光线") -> "lighting_analyst"
            agentName.contains("修图") -> "retouch_advisor"
            agentName.contains("照片") -> "photo_analyst"
            agentName.contains("图效") -> "image_enhancer"
            else -> ""
        }
        val doneEvent = events.firstOrNull { it.type == "agent_done" && it.agentName == agentName }
        val errorEvent = events.firstOrNull { it.type == "agent_error" && it.agentName == agentName }
        val state = when {
            errorEvent != null -> "error"
            doneEvent != null -> "done"
            else -> "working"
        }
        AgentStatusInfo(agentName, key, state)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isNoirAurum) {
                    Modifier.border(width = 1.dp, color = Champagne.copy(alpha = 0.2f), shape = cardShape)
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isNoirAurum) ObsidianSurface else MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = cardShape,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            // 顶部：团团表情 + 状态文字
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedEmoji(
                    resourcePath = agentEmojiPath ?: emojiState.resourcePath,
                    size = 40.dp,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (isNoirAurum) ChampagneBright else MaterialTheme.colorScheme.onSurface,
                )
            }

            // Agent 活动列表（仅当有派发 Agent 时显示）
            if (agentStatusList.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(
                    color = if (isNoirAurum) GoldDivider.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outlineVariant,
                    thickness = 0.5.dp,
                )
                Spacer(Modifier.height(8.dp))

                agentStatusList.forEach { agentInfo ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 状态图标
                        val statusIcon = when (agentInfo.state) {
                            "done" -> "\u2705"
                            "error" -> "\u274C"
                            else -> "\uD83D\uDD04"
                        }
                        val statusColor = when (agentInfo.state) {
                            "done" -> if (isNoirAurum) ChampagneBright else MaterialTheme.colorScheme.primary
                            "error" -> MaterialTheme.colorScheme.error
                            else -> if (isNoirAurum) ChampagneDeep else MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = statusIcon,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = agentInfo.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor,
                            fontWeight = if (agentInfo.state == "working") FontWeight.Medium else FontWeight.Normal,
                        )
                        if (agentInfo.state == "working") {
                            Spacer(Modifier.width(4.dp))
                            // 工作中的脉冲动画指示器
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = statusColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 表情显示行（无状态文字时仅显示团团表情）。
 */
@Composable
private fun EmojiDisplayRow(
    emojiState: EmojiState = EmojiState.IDLE,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedEmoji(
            resourcePath = emojiState.resourcePath,
            size = 48.dp,
        )
    }
}

/**
 * 流式打字指示器。
 */
@Composable
private fun TypingIndicator() {
    Row(
        modifier = Modifier.padding(start = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.width(4.dp))
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)),
        )
    }
}

/**
 * 空会话提示（团团 idle 表情 + 欢迎语）。
 */
@Composable
private fun EmptyChatHint() {
    val isNoirAurum = LocalThemeStyle.current == LxThemeStyle.NOIR_AURUM
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 团团 idle 动画表情
        AnimatedEmoji(
            resourcePath = EmojiState.IDLE.resourcePath,
            size = 80.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "喵～团团在这里！",
            style = MaterialTheme.typography.bodyLarge,
            color = if (isNoirAurum) ChampagneBright else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "有什么摄影灵感想聊聊吗？",
            style = MaterialTheme.typography.bodyMedium,
            color = if (isNoirAurum) ChampagneDeep else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 底部输入栏。
 *
 * - 已选图片缩略图显示在输入栏左上角（TRAE 风格，无文件名）
 * - 图片选择按钮接入跨平台图片选择器
 * - 支持仅图片或图文混合发送
 *
 * @param text 输入文本。
 * @param onTextChange 文本变化回调。
 * @param onSend 发送回调。
 * @param isSending 是否正在发送（禁用按钮 + 显示 loading）。
 * @param pickedImageBytes 已选择的图片字节流（null 表示未选）。
 * @param onPickImage 点击图片按钮启动选择器。
 * @param onPreviewImage 点击缩略图打开全屏预览。
 * @param onRemoveImage 点击缩略图删除按钮清除已选图片。
 */
@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
    pickedImageBytes: ByteArray?,
    onPickImage: () -> Unit,
    onPreviewImage: () -> Unit,
    onRemoveImage: () -> Unit,
) {
    val isNoirAurum = LocalThemeStyle.current == LxThemeStyle.NOIR_AURUM
    val sendEnabled = !isSending && (text.isNotBlank() || pickedImageBytes != null)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // 已选图片缩略图（左上角，TRAE 风格：36×36 / 6dp 圆角 / 无文件名）
        if (pickedImageBytes != null) {
            UploadThumbnail(
                model = pickedImageBytes,
                onPreview = onPreviewImage,
                onDelete = onRemoveImage,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图片选择按钮（接入跨平台图片选择器）
            IconButton(onClick = onPickImage) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = "上传图片",
                    tint = if (isNoirAurum) Champagne else MaterialTheme.colorScheme.primary,
                )
            }

            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp),
                placeholder = {
                    Text(
                        text = "给团团说点什么...",
                        color = if (isNoirAurum) ChampagneDeep.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                maxLines = 5,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                colors = if (isNoirAurum) {
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = ObsidianSurface,
                        unfocusedContainerColor = ObsidianSurface,
                        focusedTextColor = ChampagneBright,
                        unfocusedTextColor = ChampagneBright,
                        focusedBorderColor = Champagne,
                        unfocusedBorderColor = Champagne.copy(alpha = 0.3f),
                        cursorColor = Champagne,
                        focusedLabelColor = ChampagneBright,
                        unfocusedLabelColor = ChampagneDeep,
                    )
                } else {
                    OutlinedTextFieldDefaults.colors()
                },
                trailingIcon = {
                    if (isNoirAurum) {
                        // Noir Aurum：圆形金色发送按钮 + 黑色图标
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (sendEnabled) Champagne else Champagne.copy(alpha = 0.3f))
                                .clickable(enabled = sendEnabled, onClick = onSend),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = Obsidian,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "发送",
                                    tint = Obsidian,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    } else {
                        IconButton(
                            onClick = onSend,
                            enabled = sendEnabled,
                        ) {
                            if (isSending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "发送",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}
