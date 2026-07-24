package top.mvpdark.lx.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.mvpdark.lx.data.model.AgentEvent
import top.mvpdark.lx.data.model.ChatMessage
import top.mvpdark.lx.data.model.ChatSession
import top.mvpdark.lx.core.util.currentTimeMillis
import top.mvpdark.lx.core.util.TextUpdateThrottle
import top.mvpdark.lx.core.util.toUserMessage
import top.mvpdark.lx.core.util.runCatchingCancellable
import top.mvpdark.lx.data.local.ImageCacheManager
import top.mvpdark.lx.data.local.LocalMessageStore
import top.mvpdark.lx.data.repository.ChatRepository
import top.mvpdark.lx.ui.emoji.EmojiState
import top.mvpdark.lx.ui.emoji.chooseDoneEmoji
import top.mvpdark.lx.ui.emoji.AgentType
import top.mvpdark.lx.ui.emoji.AgentEmojiState
import top.mvpdark.lx.ui.emoji.getAgentEmojiPath
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * 聊天 UI 状态。
 */
data class ChatUiState(
    val sessions: List<ChatSession> = emptyList(),
    val currentSessionId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val agentStatus: String? = null,
    val agentEvents: List<AgentEvent> = emptyList(),
    val streamingText: String = "",
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val isSessionsPanelOpen: Boolean = false,
    val error: String? = null,
    /** 团团表情状态（APNG 动画）。 */
    val emojiState: EmojiState = EmojiState.IDLE,
    /** 当前活跃 Agent 的专属表情路径（null 时显示团团主表情）。 */
    val agentEmojiPath: String? = null,
    /** 流式期间收集的图片 URL（搜索图 + AI 制图），done 时挂到 AI 消息。 */
    val pendingImages: List<String> = emptyList(),
    /** 教学模式：开启后 AI 回复会附带教学讲解。 */
    val teachingMode: Boolean = false,
)

/**
 * 聊天 ViewModel：管理会话列表、历史消息、流式收发。
 *
 * 通过 Koin 注入 [ChatRepository]。支持团团（猫娘 AI 助手）流式回复实时追加。
 */
class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val localMessageStore: LocalMessageStore,
    private val imageCacheManager: ImageCacheManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /**
     * 流式文本聚合 + 100ms 节流刷新（方案 B）。
     *
     * 用 StringBuilder 在 TextUpdateThrottle 内部聚合 delta，仅在节流窗口
     * 过期时才 publish 全量文本到 streamingText，避免每个 delta 都触发
     * StateFlow update + Compose 重组。
     */
    private var streamThrottle = TextUpdateThrottle(100L) { currentTimeMillis() }

    /** 最近一次 publish 的完整文本（用于 done 时取最终文本）。 */
    private var lastPublishedText = ""

    /** 当前流式接收 Job，切换会话时需取消以避免旧会话事件污染新会话。 */
    private var streamJob: Job? = null

    /**
     * 从文本中提取所有 [IMAGE]url[/IMAGE] 标记的图片 URL。
     * 用于解析 image_enhancer 返回的图片标记。
     */
    private fun extractImageUrls(text: String): List<String> {
        val result = mutableListOf<String>()
        val regex = Regex("""\[IMAGE\]\s*(\S+?)\s*\[/IMAGE\]""")
        regex.findAll(text).forEach { match ->
            val url = match.groupValues.getOrNull(1)?.trim()
            if (!url.isNullOrEmpty()) result.add(url)
        }
        return result
    }

    /**
     * 清除文本中的 [IMAGE]url[/IMAGE] 标记（图片已提取到 images 列表）。
     * 同时清理多余的空行。
     */
    private fun cleanImageMarkers(text: String): String {
        val cleaned = text.replace(Regex("""\[IMAGE\]\s*\S+?\s*\[/IMAGE\]"""), "")
        // 清理多余的空行
        return cleaned.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** 初始化：加载会话列表。 */
    init {
        loadSessions()
    }

    /** 加载会话列表。置顶在前，其次按 updated_at 降序。 */
    fun loadSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result: Result<List<ChatSession>> = runCatchingCancellable { chatRepository.getSessions() }
            result
                .onSuccess { list ->
                    val sorted = list.sortedWith(
                        compareByDescending<ChatSession> { it.pinned }
                            .thenByDescending { it.updatedAt }
                    )
                    _uiState.update {
                        it.copy(sessions = sorted, isLoading = false)
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = "加载会话失败: ${e.message}")
                    }
                }
        }
    }

    /** 切换会话置顶状态。 */
    fun togglePin(sessionId: String) {
        val session = _uiState.value.sessions.firstOrNull { it.id == sessionId } ?: return
        viewModelScope.launch {
            val result: Result<Unit> = runCatchingCancellable {
                if (session.pinned) {
                    chatRepository.unpinSession(sessionId)
                } else {
                    chatRepository.pinSession(sessionId)
                }
            }
            result.onSuccess {
                // 本地更新 pinned 状态并重新排序
                _uiState.update { state ->
                    val updated = state.sessions.map {
                        if (it.id == sessionId) it.copy(pinned = !session.pinned) else it
                    }
                    val sorted = updated.sortedWith(
                        compareByDescending<ChatSession> { it.pinned }
                            .thenByDescending { it.updatedAt }
                    )
                    state.copy(sessions = sorted)
                }
            }.onFailure { e ->
                _uiState.update { it.copy(error = "操作失败: ${e.message}") }
            }
        }
    }

    /** 创建新会话，并切换到聊天页。返回新会话 ID。 */
    fun createNewSession(
        title: String = "新对话",
        onCreated: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result: Result<ChatSession> = runCatchingCancellable { chatRepository.createSession(title) }
            result
                .onSuccess { session ->
                    _uiState.update {
                        it.copy(
                            sessions = listOf(session) + it.sessions,
                            isLoading = false,
                        )
                    }
                    onCreated(session.id)
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = "创建会话失败: ${e.message}")
                    }
                }
        }
    }

    /** 选择会话并加载历史消息（从本地存储加载，不依赖后端）。 */
    fun selectSession(sessionId: String) {
        if (_uiState.value.currentSessionId == sessionId) return
        // 取消进行中的流式接收 Job，避免旧会话的流式事件污染新会话界面
        streamJob?.cancel()
        streamJob = null
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    currentSessionId = sessionId,
                    messages = emptyList(),
                    streamingText = "",
                    agentStatus = null,
                    agentEvents = emptyList(),
                    isSessionsPanelOpen = false,
                    error = null,
                )
            }
            // 从本地存储加载历史消息（所有消息和图片都保存在客户端）
            val result: Result<List<ChatMessage>> = runCatchingCancellable {
                localMessageStore.getMessages(sessionId)
            }
            result
                .onSuccess { history ->
                    // W1 修复（会话切换竞态）：异步加载期间用户可能已切到其他会话，
                    // 若 A 的加载晚于 B 完成，A 的历史会写入 B 的界面。
                    // 在 update lambda 内原子校验 currentSessionId，不一致则丢弃过期结果。
                    _uiState.update {
                        if (it.currentSessionId != sessionId) it
                        else it.copy(messages = history)
                    }
                }
                .onFailure { e ->
                    // 同样仅在仍是当前会话时展示错误，避免过期错误覆盖新会话状态
                    _uiState.update {
                        if (it.currentSessionId != sessionId) it
                        else it.copy(error = "加载本地历史失败: ${e.message}")
                    }
                }
        }
    }

    /** 更新输入框文本。 */
    fun onInputTextChange(value: String) {
        _uiState.update { it.copy(inputText = value) }
    }

    /** 切换侧边栏展开/收起。 */
    fun toggleSessionsPanel() {
        _uiState.update { it.copy(isSessionsPanelOpen = !it.isSessionsPanelOpen) }
    }

    /** 切换教学模式开关。 */
    fun toggleTeachingMode() {
        _uiState.update { it.copy(teachingMode = !it.teachingMode) }
    }

    /** 发送消息并接收流式回复。 */
    fun sendMessage(
        imageUrl: String = "",
    ) {
        val state = _uiState.value
        val text = state.inputText.trim()
        if (text.isEmpty() && imageUrl.isEmpty()) return
        if (state.isSending) return

        // 立即清空输入并标记发送中
        // 重置流式节流器（方案 B：每条消息独立聚合）
        streamThrottle = TextUpdateThrottle(100L) { currentTimeMillis() }
        lastPublishedText = ""
        _uiState.update {
            it.copy(
                inputText = "",
                isSending = true,
                streamingText = "",
                agentStatus = "团团思考中...",
                agentEvents = emptyList(),
                error = null,
                emojiState = EmojiState.THINKING,
                agentEmojiPath = null,
                pendingImages = emptyList(),
            )
        }

        val pendingText = text
        val pendingImage = imageUrl

        streamJob = viewModelScope.launch {
            // 确保有会话（异步创建，避免阻塞主线程）
            var sessionId: String? = _uiState.value.currentSessionId.ifBlank { null }
            if (sessionId.isNullOrBlank()) {
                val createResult: Result<ChatSession> = runCatchingCancellable { chatRepository.createSession("新对话") }
                val newSession = createResult.getOrNull()
                if (newSession != null) {
                    _uiState.update {
                        it.copy(sessions = listOf(newSession) + it.sessions)
                    }
                    sessionId = newSession.id
                }
            }
            if (sessionId.isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        isSending = false,
                        agentStatus = null,
                        error = "没有可用会话，请稍后重试",
                        emojiState = EmojiState.IDLE,
                    )
                }
                return@launch
            }

            val currentSessionId = sessionId!!

            // 用户图片先缓存到本地（如果有网络图片或data URL）
            val userImageLocal = if (pendingImage.isNotEmpty()) {
                runCatchingCancellable { imageCacheManager.cacheImages(listOf(pendingImage)) }
                    .getOrDefault(listOf(pendingImage))
                    .firstOrNull() ?: pendingImage
            } else ""

            // 追加用户消息到列表
            val userMessage = ChatMessage(
                id = "local_${currentTimeMillis()}",
                sessionId = currentSessionId,
                role = "user",
                content = pendingText,
                images = if (userImageLocal.isNotEmpty()) listOf(userImageLocal) else emptyList(),
                timestamp = "",
            )
            _uiState.update {
                // 发送准备期间（建会话/缓存图片）用户可能已切换到其他会话：
                // 此时不抢占界面，用户消息仅落盘，避免消息出现在错误会话中
                if (it.currentSessionId.isNotBlank() && it.currentSessionId != currentSessionId) {
                    it
                } else {
                    it.copy(
                        currentSessionId = currentSessionId,
                        messages = it.messages + userMessage,
                    )
                }
            }
            // 用户消息保存到本地存储
            runCatchingCancellable { localMessageStore.saveMessage(currentSessionId, userMessage) }

            // 教学模式：在消息前添加标记，后端可据此切换教学模式
            val streamText = if (_uiState.value.teachingMode) {
                "[教学模式] $pendingText"
            } else {
                pendingText
            }
            // 流式接收 AgentEvent（含异常保护，防止崩溃和 isSending 卡死）
            try {
                chatRepository.sendMessageStream(currentSessionId, streamText, pendingImage)
                    .collect { event: AgentEvent ->
                        handleAgentEvent(currentSessionId, event)
                    }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update {
                    it.copy(
                        error = "发送失败: ${e.toUserMessage()}",
                        emojiState = EmojiState.IDLE,
                    )
                }
            }
            // 流结束：如果表情仍在思考/工作状态，回 idle
            _uiState.update {
                if (it.emojiState == EmojiState.THINKING || it.emojiState == EmojiState.WORKING) {
                    it.copy(isSending = false, agentStatus = null, emojiState = EmojiState.IDLE)
                } else {
                    it.copy(isSending = false, agentStatus = null)
                }
            }

            // 自动命名：如果会话标题还是默认的"新对话"，用首条消息内容生成标题
            val currentSession = _uiState.value.sessions.find { it.id == currentSessionId }
            if (currentSession != null && (currentSession.title.isBlank() || currentSession.title == "新对话")) {
                val autoTitle = pendingText.trim().take(20).ifBlank { "新对话" }
                runCatchingCancellable { chatRepository.renameSession(currentSessionId, autoTitle) }
                _uiState.update { state ->
                    state.copy(
                        sessions = state.sessions.map { s ->
                            if (s.id == currentSessionId) s.copy(title = autoTitle) else s
                        },
                    )
                }
            }
        }
    }

    /** 处理单个 AgentEvent，更新 UI 状态。 */
    private suspend fun handleAgentEvent(sessionId: String, event: AgentEvent) {
        when (event.type) {
            "auth_ok" -> Unit
            "routing" -> {
                _uiState.update {
                    it.copy(
                        agentStatus = "团团正在理解你的需求...",
                        emojiState = EmojiState.THINKING,
                    )
                }
            }
            "dispatch" -> {
                val agents = event.agentsDispatched.joinToString("、").ifBlank { "团团" }
                // 匹配第一个派发 Agent 的 working 表情
                val agentEmoji = event.agentsDispatched.firstNotNullOfOrNull { name ->
                    AgentType.fromName(name)?.let { getAgentEmojiPath(it, AgentEmojiState.WORKING) }
                }
                _uiState.update {
                    it.copy(
                        agentStatus = "团团派出了：$agents",
                        emojiState = EmojiState.WORKING,
                        agentEmojiPath = agentEmoji,
                    )
                }
            }
            "status" -> {
                if (event.content.isNotBlank()) {
                    _uiState.update { it.copy(agentStatus = event.content) }
                }
            }
            "synthesis_start" -> {
                _uiState.update {
                    it.copy(
                        agentStatus = "团团整理回答中...",
                        emojiState = EmojiState.WORKING,
                    )
                }
            }
            "agent_done" -> {
                val msg = "${event.agentName}完成"
                // image_enhancer 完成时，提取图片 URL
                val newImages = mutableListOf<String>()
                if (event.agentKey == "image_enhancer") {
                    if (event.imageUrl.isNotEmpty()) {
                        newImages.add(event.imageUrl)
                    }
                    // 兼容：从 content 中解析 [IMAGE]url[/IMAGE] 标记
                    extractImageUrls(event.content).forEach { newImages.add(it) }
                }
                // 匹配完成 Agent 的 done 表情
                val agentEmoji = AgentType.fromName(event.agentName)?.let {
                    getAgentEmojiPath(it, AgentEmojiState.DONE)
                }
                _uiState.update {
                    it.copy(
                        agentStatus = msg,
                        agentEvents = it.agentEvents + event,
                        pendingImages = it.pendingImages + newImages,
                        agentEmojiPath = agentEmoji,
                    )
                }
            }
            "agent_error" -> {
                // 匹配出错 Agent 的 error 表情
                val agentEmoji = AgentType.fromName(event.agentName)?.let {
                    getAgentEmojiPath(it, AgentEmojiState.ERROR)
                }
                _uiState.update {
                    it.copy(
                        agentStatus = "${event.agentName}出错",
                        agentEvents = it.agentEvents + event,
                        agentEmojiPath = agentEmoji,
                    )
                }
            }
            "delta" -> {
                // 流式增量追加（方案 B：StringBuilder 聚合 + 100ms 节流刷新 UI）
                // W2 修复（发送中切换会话串台）：流式期间用户可能已切到其他会话，
                // 仅当事件属于当前可见会话时才追加 streamingText；
                // 否则跳过 UI 更新（本地存储不受影响，仍按原 sessionId 归档）。
                val published = streamThrottle.append(event.content)
                if (published != null) {
                    lastPublishedText = published
                    _uiState.update {
                        if (it.currentSessionId != sessionId) it
                        else it.copy(streamingText = published)
                    }
                }
            }
            "search_image" -> {
                // 搜索图片：content 字段就是图片 URL，收集到 pendingImages
                val imgUrl = event.content.trim()
                _uiState.update {
                    it.copy(
                        agentEvents = it.agentEvents + event,
                        pendingImages = if (imgUrl.isNotEmpty()) it.pendingImages + imgUrl else it.pendingImages,
                    )
                }
            }
            "done" -> {
                // 流结束：flush 节流器中剩余文本，取最终完整文本
                val flushed = streamThrottle.flush()
                if (flushed != null) {
                    lastPublishedText = flushed
                    _uiState.update {
                        if (it.currentSessionId != sessionId) it
                        else it.copy(streamingText = flushed)
                    }
                }
                // 取最终完整文本：优先 flushed（有未发布数据），否则用 lastPublishedText
                val fullText = flushed ?: lastPublishedText
                val collectedImages = _uiState.value.pendingImages
                // 清理文本中的 [IMAGE]url[/IMAGE] 标记（已提取到 images）
                val cleanedText = cleanImageMarkers(fullText)
                if (cleanedText.isNotEmpty() || collectedImages.isNotEmpty()) {
                    // 图片先下载缓存到本地，保存本地路径（不依赖后端图片URL）
                    val localImages = if (collectedImages.isNotEmpty()) {
                        runCatchingCancellable { imageCacheManager.cacheImages(collectedImages) }
                            .getOrDefault(collectedImages)
                    } else emptyList()
                    val aiMessage = ChatMessage(
                        id = "ai_${currentTimeMillis()}",
                        sessionId = sessionId,
                        role = "assistant",
                        content = cleanedText,
                        images = localImages,
                        timestamp = "",
                    )
                    // W2 修复（发送中切换会话串台）：仅当当前会话仍是该流所属的 sessionId 时，
                    // 才把消息追加到 UI 消息列表；否则丢弃 UI 更新，但本地存储仍按正确 sessionId 归档。
                    _uiState.update {
                        if (it.currentSessionId != sessionId) it
                        else it.copy(
                            messages = it.messages + aiMessage,
                        )
                    }
                    // AI消息保存到本地存储（始终执行，不受会话切换影响）
                    runCatchingCancellable { localMessageStore.saveMessage(sessionId, aiMessage) }
                }
                // W2 修复：仅当当前会话仍是该流所属的 sessionId 时才清理流式状态；
                // 否则保留新会话的流式状态。
                _uiState.update {
                    if (it.currentSessionId != sessionId) it
                    else it.copy(
                        streamingText = "",
                        agentStatus = null,
                        pendingImages = emptyList(),
                        agentEmojiPath = null,
                    )
                }
                // 仅当仍是当前会话时才切换表情，避免旧 done 事件覆盖新会话表情
                if (_uiState.value.currentSessionId == sessionId) {
                    val doneEmoji = chooseDoneEmoji(cleanedText)
                    setEmojiWithAutoRevert(doneEmoji)
                }
            }
            "error" -> {
                // W2 修复：isSending 属于全局发送状态（阻塞输入框），即使切换了会话也必须复位；
                // 但错误文案、状态和表情属于当前会话 UI，仅在仍是该会话时才更新，避免串台。
                _uiState.update {
                    if (it.currentSessionId != sessionId) {
                        it.copy(isSending = false)
                    } else {
                        it.copy(
                            isSending = false,
                            agentStatus = null,
                            error = event.error.ifBlank { event.content.ifBlank { "团团开小差了" } },
                        )
                    }
                }
                // 出错：显示道歉表情，3 秒后自动回 idle（同样仅在当前会话展示）
                if (_uiState.value.currentSessionId == sessionId) {
                    setEmojiWithAutoRevert(EmojiState.APOLOGIZING)
                }
            }
            else -> Unit
        }
    }

    /**
     * 设置表情状态，happy/apologizing 3 秒后自动回 idle。
     */
    private fun setEmojiWithAutoRevert(state: EmojiState) {
        _uiState.update { it.copy(emojiState = state) }
        if (state == EmojiState.HAPPY || state == EmojiState.APOLOGIZING) {
            viewModelScope.launch {
                delay(3000)
                // 仅当仍处于同一状态时才回退
                if (_uiState.value.emojiState == state) {
                    _uiState.update { it.copy(emojiState = EmojiState.IDLE) }
                }
            }
        }
    }

    /** 清除错误提示。 */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** 删除会话（同时清理后端和本地存储）。 */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            runCatchingCancellable { chatRepository.deleteSession(sessionId) }
            runCatchingCancellable { localMessageStore.deleteSessionMessages(sessionId) }
            _uiState.update { state ->
                val remaining = state.sessions.filterNot { s -> s.id == sessionId }
                if (state.currentSessionId == sessionId) {
                    // 删除的是当前打开的会话：回到新对话界面，
                    // 避免残留已删除会话的消息、以及后续消息发往已删除会话
                    state.copy(
                        sessions = remaining,
                        currentSessionId = "",
                        messages = emptyList(),
                        streamingText = "",
                        agentStatus = null,
                        agentEvents = emptyList(),
                        pendingImages = emptyList(),
                        agentEmojiPath = null,
                        isSending = false,
                        error = null,
                    )
                } else {
                    state.copy(sessions = remaining)
                }
            }
        }
    }
}
