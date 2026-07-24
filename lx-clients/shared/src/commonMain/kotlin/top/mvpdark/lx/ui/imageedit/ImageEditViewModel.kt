package top.mvpdark.lx.ui.imageedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.mvpdark.lx.core.util.PlatformLogger
import top.mvpdark.lx.core.util.toUserMessage
import top.mvpdark.lx.data.model.Bbox
import top.mvpdark.lx.core.util.decodeBase64ToBytes
import top.mvpdark.lx.data.model.DetectedObject
import top.mvpdark.lx.data.model.Preset
import top.mvpdark.lx.data.model.CollaborativeStage
import top.mvpdark.lx.data.repository.ImageEditRepository
import top.mvpdark.lx.core.util.SingleFlightGate

/**
 * 图像编辑 UI 状态。
 *
 * 状态机步骤：upload / analyzing / edit / generating / removingBg / enhancing / result。
 * 其中 analyzing / generating / removingBg / enhancing 为瞬态加载步骤，edit 为核心交互步骤。
 *
 * @property step 当前流程步骤
 * @property originalBytes 原始图片字节流（用于后续 API 调用）
 * @property imageDisplayUrl 用于 Coil 显示的 data URL
 * @property objects VLM 检测后的物体列表
 * @property promptText 未选中物体时的全局改图描述
 * @property selectedPrompt 选中物体时的区域改图描述
 * @property resultUrl 生成结果图 URL（data URL）
 * @property error 错误提示文案
 * @property isEditing 是否正在生成改图
 * @property isProcessingBg 是否正在去背景/增强
 * @property history 操作历史记录栈（用于撤销）
 */
// ByteArray 存于 data class：originalBytes 为图片字节流，用于 API 调用。
// data class 默认 equals/hashCode 基于引用比较 ByteArray，此处可接受：
// ImageEditUiState 通过 StateFlow update 创建新实例触发重组，不依赖结构化相等。
// 历史栈已限制为 5 条（MAX_HISTORY_SIZE），内存占用可控。
@Suppress("ArrayInDataClass")
data class ImageEditUiState(
    val step: ImageEditViewModel.Step = ImageEditViewModel.Step.Upload,
    val originalBytes: ByteArray? = null,
    val imageDisplayUrl: String? = null,
    val objects: List<DetectedObject> = emptyList(),
    val promptText: String = "",
    val selectedPrompt: String = "",
    val resultUrl: String? = null,
    val error: String? = null,
    val isEditing: Boolean = false,
    val isProcessingBg: Boolean = false,
    val history: List<ImageEditViewModel.HistoryEntry> = emptyList(),
    val presets: List<Preset> = emptyList(),
    val isCollaborative: Boolean = false,
    val collaborativeStages: List<CollaborativeStage> = emptyList(),
    val isStyleTransferring: Boolean = false,
    val styleAnalysis: String? = null,
)

/**
 * 图像编辑 ViewModel：管理上传 → VLM 检测 → 编辑 → 生成 的完整流程。
 *
 * 通过 Koin 注入 [ImageEditRepository]。
 * 支持物体选中、区域标注改图、直接改图、去背景、图片增强等功能。
 *
 * 流程说明：
 * 1. [onPickImage] — 用户选图后触发，并行执行上传与 VLM 检测，直接进入编辑模式
 * 2. [toggleSelect] — 在 Edit 步骤切换物体选中状态
 * 3. [onPromptChange] — 更新改图描述（区分选中 / 未选中场景）
 * 4. [startEdit] — 触发改图生成（有选中走 annotated 接口，无选中走普通接口）
 * 5. [removeBackground] — 一键去除图片背景
 * 6. [enhanceImage] — 图片超分辨率增强
 * 7. [resetEdit] / [resetAll] — 重置编辑或全部状态
 * 8. [undo] — 撤销上一步操作
 * 9. [exportImage] — 导出图片（支持 jpeg/png/webp 格式）
 */
class ImageEditViewModel(
    private val repository: ImageEditRepository,
) : ViewModel() {

    /** 流程步骤枚举。 */
    enum class Step { Upload, Analyzing, Edit, Generating, RemovingBg, Enhancing, Collaborative, StyleTransfer, Result }

    /** 历史记录条目 */
    // ByteArray 存于 data class：originalBytes 仅为撤销时恢复图片引用，不做结构化比较。
    // 历史栈已限制为 5 条，避免 ByteArray 大量累积。如需精确比较可改用 contentEquals/contentHashCode。
    @Suppress("ArrayInDataClass")
    data class HistoryEntry(
        val imageDisplayUrl: String?,
        val originalBytes: ByteArray?,
        val step: Step,
        val operation: String, // "edit", "rembg", "enhance", "preset", "collaborative", "style_transfer"
    )

    companion object {
        /** 历史记录最大条数（限制为 5 条，控制内存占用与 ByteArray 引用持有）。 */
        private const val MAX_HISTORY_SIZE = 5
    }

    private val _uiState = MutableStateFlow(ImageEditUiState())
    val uiState: StateFlow<ImageEditUiState> = _uiState.asStateFlow()

    /** 重入保护：防止 onPickImage / startEdit 被并发调用导致状态错乱。 */
    private val isProcessing = MutableStateFlow(false)
    private val presetLoadGate = SingleFlightGate()

    /**
     * 推入历史记录（最多 5 条，跳过连续重复状态，不做 copyOf）。
     *
     * - 连续重复：与栈顶条目的 operation 和 imageDisplayUrl 相同时跳过，
     *   避免同一操作重复入栈导致撤销栈膨胀。
     * - 上限 5 条：超出时丢弃最早的条目（takeLast），ByteArray 引用直接持有不拷贝。
     */
    private fun pushHistory(operation: String): List<HistoryEntry> {
        val current = _uiState.value
        val lastEntry = current.history.lastOrNull()
        // 跳过连续重复状态（同一操作 + 同一图片 URL）
        if (lastEntry != null &&
            lastEntry.operation == operation &&
            lastEntry.imageDisplayUrl == current.imageDisplayUrl
        ) {
            return current.history
        }
        val entry = HistoryEntry(
            imageDisplayUrl = current.imageDisplayUrl,
            originalBytes = current.originalBytes,
            step = current.step,
            operation = operation,
        )
        return (current.history + entry).takeLast(MAX_HISTORY_SIZE)
    }

    /**
     * 用户选图后调用：上传 → VLM 检测 → 进入编辑模式。
     *
     * 上传与 VLM 检测并行执行。
     * VLM 检测失败时不阻塞流程，用户可手动框选或直接改图。
     *
     * @param bytes 原始图片字节流
     */
    fun onPickImage(bytes: ByteArray) {
        if (!isProcessing.compareAndSet(false, true)) return
        val currentStep = _uiState.value.step
        if (currentStep == Step.Analyzing || currentStep == Step.Generating ||
            currentStep == Step.RemovingBg || currentStep == Step.Enhancing
        ) {
            isProcessing.value = false
            return
        }

        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        step = Step.Analyzing,
                        originalBytes = bytes,
                        error = null,
                    )
                }

                // 并行：上传 + VLM 检测
                val (uploadResult, detectResult) = coroutineScope {
                    val uploadDeferred = async { repository.uploadImage(bytes, "image.jpg") }
                    val detectDeferred = async { repository.vlmDetect(bytes, "image.jpg") }
                    uploadDeferred.await() to detectDeferred.await()
                }

                val displayUrl = if (uploadResult.success) uploadResult.image else null
                val detectWarning = if (!detectResult.success) {
                    detectResult.error.ifBlank { "物品检测失败，可手动框选或直接改图" }
                } else {
                    null
                }
                val detectedObjects = if (detectResult.success) {
                    detectResult.objects.mapIndexed { idx, obj ->
                        DetectedObject(
                            id = obj.id ?: idx,
                            label = obj.label,
                            bbox = obj.bbox ?: Bbox(0f, 0f, 0f, 0f),
                        )
                    }
                } else {
                    emptyList()
                }

                if (displayUrl == null) {
                    _uiState.update {
                        it.copy(
                            step = Step.Upload,
                            error = uploadResult.error.ifEmpty { "图片上传失败" },
                        )
                    }
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        step = Step.Edit,
                        imageDisplayUrl = displayUrl,
                        objects = detectedObjects,
                        error = detectWarning,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(
                        step = Step.Upload,
                        error = e.toUserMessage(),
                    )
                }
            } finally {
                isProcessing.value = false
            }
        }
    }

    /**
     * 切换物体选中状态。
     *
     * @param id 物体 ID
     */
    fun toggleSelect(id: Int) {
        _uiState.update {
            it.copy(
                objects = it.objects.map { obj ->
                    if (obj.id == id) obj.copy(selected = !obj.selected) else obj
                },
            )
        }
    }

    /**
     * 更新改图描述。
     *
     * @param text 输入文本
     * @param selected 是否为选中物体的输入框（true 更新 selectedPrompt，false 更新 promptText）
     */
    fun onPromptChange(text: String, selected: Boolean) {
        _uiState.update {
            if (selected) {
                it.copy(selectedPrompt = text)
            } else {
                it.copy(promptText = text)
            }
        }
    }

    /**
     * 开始改图：收集选中区域 + prompt → 调用 API → 显示结果。
     *
     * - 有选中物体时走 [ImageEditRepository.editImageAnnotated]（带区域标注）
     * - 无选中物体时走 [ImageEditRepository.editImage]（直接改图）
     */
    fun startEdit() {
        if (!isProcessing.compareAndSet(false, true)) return
        val state = _uiState.value
        val bytes = state.originalBytes
        if (bytes == null) {
            isProcessing.value = false
            return
        }

        viewModelScope.launch {
            try {
                val currentHistory = pushHistory("edit")
                _uiState.update {
                    it.copy(step = Step.Generating, isEditing = true, error = null, history = currentHistory)
                }

                val selectedObjects = state.objects.filter { it.selected }
                val result = if (selectedObjects.isNotEmpty()) {
                    val prompt = state.selectedPrompt.ifEmpty { "优化选中区域" }
                    repository.editImageAnnotated(bytes, "image.jpg", prompt, selectedObjects)
                } else {
                    val prompt = state.promptText.ifEmpty { "优化图片" }
                    repository.editImage(bytes, "image.jpg", prompt)
                }

                if (result.success && result.image.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            step = Step.Result,
                            resultUrl = result.image,
                            isEditing = false,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            step = Step.Edit,
                            isEditing = false,
                            error = result.error.ifEmpty { "改图失败，请重试" },
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                PlatformLogger.e("ImageEditViewModel", "startEdit failed", e)
                _uiState.update {
                    it.copy(
                        step = Step.Edit,
                        isEditing = false,
                        error = e.toUserMessage(),
                    )
                }
            } finally {
                isProcessing.value = false
            }
        }
    }

    /**
     * 一键去除图片背景。调用后端 /api/rembg-remove 接口。
     * 成功后直接显示结果图。
     */
    fun removeBackground() {
        if (!isProcessing.compareAndSet(false, true)) return
        val bytes = _uiState.value.originalBytes
        if (bytes == null) {
            isProcessing.value = false
            return
        }

        viewModelScope.launch {
            try {
                val currentHistory = pushHistory("rembg")
                _uiState.update {
                    it.copy(step = Step.RemovingBg, isProcessingBg = true, error = null, history = currentHistory)
                }

                val result = repository.removeBackground(bytes, "image.jpg")

                if (result.success && result.image.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            step = Step.Result,
                            resultUrl = result.image,
                            isProcessingBg = false,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            step = Step.Edit,
                            isProcessingBg = false,
                            error = result.error.ifEmpty { "去背景失败，请重试" },
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                PlatformLogger.e("ImageEditViewModel", "removeBackground failed", e)
                _uiState.update {
                    it.copy(
                        step = Step.Edit,
                        isProcessingBg = false,
                        error = e.toUserMessage(),
                    )
                }
            } finally {
                isProcessing.value = false
            }
        }
    }

    /**
     * 图片增强（超分辨率）。调用后端 /api/enhance 接口。
     * 成功后直接显示结果图。
     *
     * @param mode 增强模式，默认 "super_resolution"
     * @param scale 放大倍数，默认 2
     */
    fun enhanceImage(mode: String = "super_resolution", scale: Int = 2) {
        if (!isProcessing.compareAndSet(false, true)) return
        val bytes = _uiState.value.originalBytes
        if (bytes == null) {
            isProcessing.value = false
            return
        }

        viewModelScope.launch {
            try {
                val currentHistory = pushHistory("enhance")
                _uiState.update {
                    it.copy(step = Step.Enhancing, isProcessingBg = true, error = null, history = currentHistory)
                }

                val result = repository.enhanceImage(bytes, "image.jpg", mode, scale)

                if (result.success && result.image.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            step = Step.Result,
                            resultUrl = result.image,
                            isProcessingBg = false,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            step = Step.Edit,
                            isProcessingBg = false,
                            error = result.error.ifEmpty { "图片增强失败，请重试" },
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                PlatformLogger.e("ImageEditViewModel", "enhanceImage failed", e)
                _uiState.update {
                    it.copy(
                        step = Step.Edit,
                        isProcessingBg = false,
                        error = e.toUserMessage(),
                    )
                }
            } finally {
                isProcessing.value = false
            }
        }
    }


    /** 加载预设列表 */
    fun loadPresets() {
        if (_uiState.value.presets.isNotEmpty() || !presetLoadGate.tryStart()) return
        viewModelScope.launch {
            try {
                val response = repository.getPresets()
                if (response.success) {
                    _uiState.update { it.copy(presets = response.presets) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                PlatformLogger.e("ImageEditViewModel", "loadPresets failed", e)
            } finally {
                presetLoadGate.finish()
            }
        }
    }

    /** 应用预设 */
    fun applyPreset(presetId: String) {
        if (!isProcessing.compareAndSet(false, true)) return
        val bytes = _uiState.value.originalBytes
        if (bytes == null) {
            isProcessing.value = false
            return
        }

        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isProcessingBg = true, error = null) }

                // 推入历史（最多 5 条 + 跳过连续重复）
                val currentHistory = pushHistory("preset")

                _uiState.update { it.copy(history = currentHistory) }

                val result = repository.applyPreset(bytes, "image.jpg", presetId)

                if (result.success && result.image.isNotEmpty()) {
                    val newBytes = decodeBase64ToBytes(result.image)
                    _uiState.update {
                        it.copy(
                            resultUrl = result.image,
                            originalBytes = newBytes ?: it.originalBytes,
                            step = Step.Result,
                            isProcessingBg = false,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isProcessingBg = false,
                            error = result.error.ifEmpty { "预设应用失败，请重试" },
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                PlatformLogger.e("ImageEditViewModel", "applyPreset failed", e)
                _uiState.update {
                    it.copy(isProcessingBg = false, error = e.toUserMessage())
                }
            } finally {
                isProcessing.value = false
            }
        }
    }

    /**
     * 多Agent协作修图。
     * 调用后端 /api/collaborative-edit，展示多阶段Agent协作流程。
     */
    fun startCollaborativeEdit() {
        if (!isProcessing.compareAndSet(false, true)) return
        val bytes = _uiState.value.originalBytes
        if (bytes == null) {
            isProcessing.value = false
            return
        }

        viewModelScope.launch {
            try {
                val currentHistory = pushHistory("collaborative")
                _uiState.update {
                    it.copy(step = Step.Collaborative, isCollaborative = true, error = null, history = currentHistory)
                }

                val result = repository.collaborativeEdit(bytes, "image.jpg")

                if (result.success && result.image.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            step = Step.Result,
                            resultUrl = result.image,
                            isCollaborative = false,
                            collaborativeStages = result.stages,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            step = Step.Edit,
                            isCollaborative = false,
                            error = result.error.ifEmpty { "协作修图失败，请重试" },
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                PlatformLogger.e("ImageEditViewModel", "startCollaborativeEdit failed", e)
                _uiState.update {
                    it.copy(
                        step = Step.Edit,
                        isCollaborative = false,
                        error = e.toUserMessage(),
                    )
                }
            } finally {
                isProcessing.value = false
            }
        }
    }

    /**
     * 跨照片风格迁移。
     * @param referenceBytes 参考图字节流
     */
    fun startStyleTransfer(referenceBytes: ByteArray) {
        if (!isProcessing.compareAndSet(false, true)) return
        val bytes = _uiState.value.originalBytes
        if (bytes == null) {
            isProcessing.value = false
            return
        }

        viewModelScope.launch {
            try {
                val currentHistory = pushHistory("style_transfer")
                _uiState.update {
                    it.copy(step = Step.StyleTransfer, isStyleTransferring = true, error = null, history = currentHistory)
                }

                val result = repository.styleTransfer(bytes, "image.jpg", referenceBytes, "reference.jpg")

                if (result.success && result.image.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            step = Step.Result,
                            resultUrl = result.image,
                            isStyleTransferring = false,
                            styleAnalysis = result.styleAnalysis.ifEmpty { null },
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            step = Step.Edit,
                            isStyleTransferring = false,
                            error = result.error.ifEmpty { "风格迁移失败，请重试" },
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                PlatformLogger.e("ImageEditViewModel", "startStyleTransfer failed", e)
                _uiState.update {
                    it.copy(
                        step = Step.Edit,
                        isStyleTransferring = false,
                        error = e.toUserMessage(),
                    )
                }
            } finally {
                isProcessing.value = false
            }
        }
    }

    /** 撤销上一步操作 */
    fun undo() {
        val state = _uiState.value
        if (state.history.isEmpty()) return
        val lastEntry = state.history.last()
        _uiState.update {
            it.copy(
                step = lastEntry.step,
                imageDisplayUrl = lastEntry.imageDisplayUrl,
                originalBytes = lastEntry.originalBytes,
                resultUrl = null,
                error = null,
                history = it.history.dropLast(1),
            )
        }
    }

    /** 是否可以撤销 */
    fun canUndo(): Boolean = _uiState.value.history.isNotEmpty()

    /**
     * 导出图片。支持 jpeg/png/webp 格式，可调质量。
     *
     * @param format 导出格式（jpeg / png / webp）
     * @param quality 导出质量（10-100），仅对 jpeg/webp 有效
     */
    fun exportImage(format: String, quality: Int) {
        if (!isProcessing.compareAndSet(false, true)) return
        val bytes = _uiState.value.originalBytes
        if (bytes == null) {
            isProcessing.value = false
            return
        }

        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isProcessingBg = true, error = null) }

                val result = repository.exportImage(bytes, "image.jpg", format, quality)

                if (result.success && result.image.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            resultUrl = result.image,
                            step = Step.Result,
                            isProcessingBg = false,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isProcessingBg = false,
                            error = result.error.ifEmpty { "导出失败，请重试" },
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                PlatformLogger.e("ImageEditViewModel", "exportImage failed", e)
                _uiState.update {
                    it.copy(isProcessingBg = false, error = e.toUserMessage())
                }
            } finally {
                isProcessing.value = false
            }
        }
    }

    /** 重新编辑 — 回到编辑步骤，保留图片和标注，清除结果与选中描述。 */
    fun resetEdit() {
        _uiState.update {
            it.copy(
                step = Step.Edit,
                resultUrl = null,
                selectedPrompt = "",
                error = null,
            )
        }
    }

    /** 重新上传 — 重置所有状态，回到上传步骤。 */
    fun resetAll() {
        _uiState.value = ImageEditUiState()
    }

    /**
     * 重新开始：重置所有状态，回到上传步骤。
     *
     * 注意：当前与 [resetAll] 行为完全一致，保留独立方法名以兼容现有调用方。
     * 若后续需要"继续编辑"语义（回到 Edit 步骤并保留图片），应改为调用 [resetEdit]。
     */
    fun continueEdit() {
        resetAll()
    }

    /** 清除错误提示。 */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
