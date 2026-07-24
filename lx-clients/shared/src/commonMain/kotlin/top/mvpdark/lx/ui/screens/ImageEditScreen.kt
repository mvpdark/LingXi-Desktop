package top.mvpdark.lx.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import top.mvpdark.lx.ui.emoji.AnimatedEmoji
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.koin.compose.viewmodel.koinViewModel
import top.mvpdark.lx.data.model.DetectedObject
import top.mvpdark.lx.data.model.CollaborativeStage
import top.mvpdark.lx.ui.imageedit.ImageEditUiState
import top.mvpdark.lx.ui.imageedit.ImageEditViewModel
import top.mvpdark.lx.ui.imageedit.rememberImagePickerLauncher

// 对齐 image-edit.js 的 Konva 颜色常量
private val AnnotationRed = Color(0xFFFA5151)
private val AnnotationBlue = Color(0xFF2563EB)

/**
 * 图像编辑页面。
 *
 * 状态机对齐 image-edit.js 的 step：
 * - Upload：显示选择图片按钮
 * - Analyzing：显示加载动画 "AI 正在分析图片..."
 * - Edit：Coil 显示原图 + Canvas 绘制标注 + 点击选中 + 输入框 + 改图按钮
 * - Generating：显示加载动画 "AI 正在生成..."
 * - Result：显示结果图 + 继续编辑 / 重新编辑 / 重新开始按钮
 *
 * 文件选择器通过 [rememberImagePickerLauncher] 由各平台实现，选好图片后调用
 * [ImageEditViewModel.onPickImage] 传入字节流。
 *
 * @param viewModel 图像编辑 ViewModel（Koin 注入）。
 * @param onBack 返回上一页回调（Compose Navigation popBackStack）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageEditScreen(
    viewModel: ImageEditViewModel = koinViewModel(),
    onBack: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val launchPicker = rememberImagePickerLauncher { bytes ->
        if (bytes != null) viewModel.onPickImage(bytes)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "修图",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    if (state.step != ImageEditViewModel.Step.Upload) {
                        IconButton(onClick = viewModel::resetAll) {
                            Icon(Icons.Default.Refresh, contentDescription = "换一张图")
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (state.step) {
                ImageEditViewModel.Step.Upload -> UploadContent(
                    error = state.error,
                    onPickImage = launchPicker,
                )

                ImageEditViewModel.Step.Analyzing -> LoadingContent(
                    message = "AI 正在分析图片...",
                    emojiPath = "files/emoji/agents/animated/photo_analyst/working.apng",
                )

                ImageEditViewModel.Step.Edit -> EditContent(
                    state = state,
                    viewModel = viewModel,
                )

                ImageEditViewModel.Step.Generating -> LoadingContent(
                    message = "AI 正在生成修改后的图片...",
                    emojiPath = "files/emoji/agents/animated/image_enhancer/working.apng",
                )

                ImageEditViewModel.Step.RemovingBg -> LoadingContent(
                    message = "正在去除背景...",
                    emojiPath = "files/emoji/agents/animated/photo_analyst/working.apng",
                )

                ImageEditViewModel.Step.Enhancing -> LoadingContent(
                    message = "正在增强图片...",
                    emojiPath = "files/emoji/agents/animated/image_enhancer/working.apng",
                )

                ImageEditViewModel.Step.Collaborative -> LoadingContent(
                    message = "多 Agent 协作修图中...",
                    emojiPath = "files/emoji/agents/animated/photo_analyst/working.apng",
                )

                ImageEditViewModel.Step.StyleTransfer -> LoadingContent(
                    message = "正在迁移风格...",
                    emojiPath = "files/emoji/agents/animated/image_enhancer/working.apng",
                )

                ImageEditViewModel.Step.Result -> ResultContent(
                    state = state,
                    viewModel = viewModel,
                )
            }
        }
    }
}

// ============================================================
// Upload 步骤
// ============================================================

/**
 * 上传步骤：显示选择图片按钮 + 提示文案。
 */
@Composable
private fun UploadContent(
    error: String?,
    onPickImage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(24.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.AddAPhoto,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = "点击上传图片",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "支持 JPG、PNG",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onPickImage,
            shape = RoundedCornerShape(24.dp),
        ) {
            Icon(
                imageVector = Icons.Default.AddAPhoto,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("选择图片")
        }
        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ============================================================
// Loading 步骤（Analyzing / Generating）
// ============================================================

/**
 * 通用加载步骤：圆形进度指示器 + 提示文案。
 */
@Composable
private fun LoadingContent(
    message: String,
    emojiPath: String = "files/emoji/animated/thinking.apng",
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // APNG 动画表情替代纯进度圈
        AnimatedEmoji(
            resourcePath = emojiPath,
            size = 72.dp,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ============================================================
// Edit 步骤（核心交互）
// ============================================================

/**
 * 编辑步骤：原图 + Canvas 标注 + 物体列表 + 输入框。
 *
 * - 用 Coil AsyncImage 显示原图（ContentScale.FillWidth）
 * - Canvas 覆盖在图片上绘制标注（多边形 / bbox）+ 编号圆圈
 * - 点击 Canvas 上的物体可选中/取消选中（多边形用射线法命中检测）
 * - 图片下方显示物体标签列表（可点击选中）
 * - 底部输入栏：有选中显示区域描述输入框，无选中显示全局描述输入框 + 改图按钮
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditContent(
    state: ImageEditUiState,
    viewModel: ImageEditViewModel,
) {
    // TODO: 如果 objects 改为 SnapshotStateList，可用 derivedStateOf 优化
    val hasSelected = state.objects.any { it.selected }
    val selectedCount = state.objects.count { it.selected }
    var showPresetDialog by remember { mutableStateOf(false) }
    // 预设加载去重：防止重复请求（方案 B）
    var isLoadingPresets by remember { mutableStateOf(false) }

    // 预设到达后重置加载状态
    LaunchedEffect(state.presets) {
        if (state.presets.isNotEmpty()) {
            isLoadingPresets = false
        }
    }
    // 安全兜底：5 秒超时自动重置，防止加载失败后 isLoadingPresets 卡死
    LaunchedEffect(isLoadingPresets) {
        if (isLoadingPresets) {
            delay(5000)
            isLoadingPresets = false
        }
    }

    // 风格迁移参考图选择器
    val launchReferencePicker = rememberImagePickerLauncher { bytes ->
        if (bytes != null) {
            viewModel.startStyleTransfer(bytes)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 可滚动的内容区：图片 + 标注 + 物体列表 + 错误提示
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            // 原图 + Canvas 标注
            if (state.imageDisplayUrl != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    AsyncImage(
                        model = state.imageDisplayUrl,
                        contentDescription = "原图",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    // Canvas 覆盖在图片上，绘制标注并处理点击
                    val objects = state.objects
                    // 跟踪 Canvas 实际尺寸（AsyncImage 加载完成后会变化）
                    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
                    // 用 rememberUpdatedState 避免每次 toggleSelect 都重启手势检测器
                    val currentObjects by rememberUpdatedState(objects)
                    val currentCanvasSize by rememberUpdatedState(canvasSize)
                    Canvas(
                        modifier = Modifier
                            .matchParentSize()
                            .onSizeChanged { canvasSize = it }
                            .semantics {
                                contentDescription = "图片标注区域，共 ${objects.size} 个检测物体"
                            }
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    val size = currentCanvasSize
                                    val canvasW = size.width.toFloat()
                                    val canvasH = size.height.toFloat()
                                    if (canvasW <= 0f || canvasH <= 0f) return@detectTapGestures
                                    val nx = offset.x / canvasW
                                    val ny = offset.y / canvasH
                                    // 读取最新的物体列表
                                    val objs = currentObjects
                                    // 遍历物体，判断点击位置是否在某个物体内
                                    val hit = objs.firstOrNull { obj ->
                                        if (obj.polygon != null && obj.polygon.size >= 3) {
                                            pointInPolygon(nx, ny, obj.polygon)
                                        } else {
                                            val b = obj.bbox
                                            nx >= b.x && nx <= b.x + b.w &&
                                                ny >= b.y && ny <= b.y + b.h
                                        }
                                    }
                                    if (hit != null) {
                                        viewModel.toggleSelect(hit.id)
                                    }
                                }
                            },
                    ) {
                        drawAnnotations(objects)
                    }
                }
            }

            // 物体标签列表
            if (state.objects.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.objects.forEach { obj ->
                        ObjectTag(
                            obj = obj,
                            onClick = { viewModel.toggleSelect(obj.id) },
                        )
                    }
                }
            } else {
                Text(
                    text = "未检测到物品，可直接输入描述进行改图",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // 已选中数量提示
            if (selectedCount > 0) {
                val labels = state.objects
                    .filter { it.selected }
                    .sortedBy { it.id }
                    .joinToString("、") { "${it.id}号 ${it.label}" }
                Text(
                    text = "已选中 $selectedCount 个区域：$labels",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // 错误信息
            if (state.error != null) {
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.clearError() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // 快捷操作按钮：去背景 + 增强
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = viewModel::removeBackground,
                modifier = Modifier.weight(1f),
                enabled = !state.isEditing && !state.isProcessingBg,
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.CropFree,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("去背景")
            }
            OutlinedButton(
                onClick = { viewModel.enhanceImage() },
                modifier = Modifier.weight(1f),
                enabled = !state.isEditing && !state.isProcessingBg,
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AutoFixHigh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("超分辨率")
            }
            OutlinedButton(
                onClick = {
                    // 预设加载去重：仅在未加载且预设为空时触发请求
                    if (viewModel.uiState.value.presets.isEmpty() && !isLoadingPresets) {
                        isLoadingPresets = true
                        viewModel.loadPresets()
                    }
                    showPresetDialog = true
                },
                modifier = Modifier.weight(1f),
                enabled = !state.isEditing && !state.isProcessingBg,
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("预设")
            }
            if (viewModel.canUndo()) {
                OutlinedButton(
                    onClick = { viewModel.undo() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Undo,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("撤销")
                }
            }
        }

        // P2 创新功能按钮：协作修图 + 风格迁移
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = viewModel::startCollaborativeEdit,
                modifier = Modifier.weight(1f),
                enabled = !state.isEditing && !state.isProcessingBg,
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("协作修图")
            }
            OutlinedButton(
                onClick = launchReferencePicker,
                modifier = Modifier.weight(1f),
                enabled = !state.isEditing && !state.isProcessingBg,
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Compare,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("风格迁移")
            }
        }

        // 预设选择对话框
        if (showPresetDialog) {
            val presets = viewModel.uiState.value.presets
            AlertDialog(
                onDismissRequest = { showPresetDialog = false },
                title = { Text("修图预设") },
                text = {
                    if (isLoadingPresets && presets.isEmpty()) {
                        // 加载中：显示进度指示器
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                    LazyColumn {
                        items(presets) { preset ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showPresetDialog = false
                                        viewModel.applyPreset(preset.id)
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(preset.icon, style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(preset.name, fontWeight = FontWeight.Medium)
                                    Text(
                                        preset.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showPresetDialog = false }) { Text("取消") }
                }
            )
        }

        // 底部输入栏
        if (hasSelected) {
            // 选中物体时：区域描述输入框 + 发送按钮
            EditInputBar(
                text = state.selectedPrompt,
                onTextChange = { viewModel.onPromptChange(it, selected = true) },
                onSend = viewModel::startEdit,
                isSending = state.isEditing,
                placeholder = if (selectedCount > 1) {
                    "描述修改，如：台面换成白色，水龙头换成金色"
                } else {
                    "描述对这个物品的修改..."
                },
            )
        } else {
            // 未选中时：全局描述输入框 + 开始改图按钮
            EditGlobalInputBar(
                text = state.promptText,
                onTextChange = { viewModel.onPromptChange(it, selected = false) },
                onSend = viewModel::startEdit,
                isSending = state.isEditing,
            )
        }
    }
}

/**
 * 物体标签：编号 + 名称，选中时高亮。
 */
@Composable
private fun ObjectTag(
    obj: DetectedObject,
    onClick: () -> Unit,
) {
    val bgColor = if (obj.selected) AnnotationBlue else AnnotationRed
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = obj.id.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = obj.label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        }
    }
}

/**
 * 选中物体时的底部输入栏：输入框 + 发送按钮。
 */
@Composable
private fun EditInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
    placeholder: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
            placeholder = { Text(placeholder) },
            maxLines = 3,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            trailingIcon = {
                IconButton(
                    onClick = onSend,
                    enabled = !isSending && text.isNotBlank(),
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
            },
        )
    }
}

/**
 * 未选中物体时的底部输入栏：输入框 + 开始改图按钮。
 */
@Composable
private fun EditGlobalInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isSending: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
            placeholder = { Text("描述你想要的修改...") },
            maxLines = 4,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onSend,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSending && text.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            if (isSending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text("生成中...")
            } else {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("开始改图")
            }
        }
    }
}

// ============================================================
// Result 步骤
// ============================================================

/**
 * 结果步骤：显示结果图 + 操作按钮。
 */
@Composable
private fun ResultContent(
    state: ImageEditUiState,
    viewModel: ImageEditViewModel,
) {
    var showExportDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.resultUrl != null) {
            AsyncImage(
                model = state.resultUrl,
                contentDescription = "生成结果",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)),
            )
        }

        Spacer(Modifier.height(20.dp))

        // 协作修图阶段展示
        if (state.collaborativeStages.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "协作修图阶段",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    state.collaborativeStages.forEach { stage ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val statusIcon = when (stage.status) {
                                "done" -> "\u2705"
                                "error" -> "\u274C"
                                else -> "\uD83D\uDD04"
                            }
                            Text(
                                text = statusIcon,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stage.agentName,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                            if (stage.content.isNotEmpty()) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stage.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // 风格分析结果展示
        if (state.styleAnalysis != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "风格分析",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.styleAnalysis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = viewModel::resetEdit,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("返回编辑")
            }
            OutlinedButton(
                onClick = { showExportDialog = true },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("导出")
            }
            Button(
                onClick = viewModel::continueEdit,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("重新开始")
            }
        }

        // 导出对话框
        if (showExportDialog) {
            var selectedFormat by remember { mutableStateOf("jpeg") }
            var quality by remember { mutableStateOf(90) }

            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { Text("导出图片") },
                text = {
                    Column {
                        Text("格式")
                        Spacer(Modifier.height(8.dp))
                        Row {
                            listOf("jpeg", "png", "webp").forEach { fmt ->
                                FilterChip(
                                    selected = selectedFormat == fmt,
                                    onClick = { selectedFormat = fmt },
                                    label = { Text(fmt.uppercase()) }
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                        if (selectedFormat != "png") {
                            Spacer(Modifier.height(16.dp))
                            Text("质量: $quality%")
                            Slider(
                                value = quality.toFloat(),
                                onValueChange = { quality = it.toInt() },
                                valueRange = 10f..100f,
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showExportDialog = false
                            viewModel.exportImage(selectedFormat, quality)
                        }
                    ) { Text("导出") }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = { showExportDialog = false }
                    ) { Text("取消") }
                }
            )
        }
    }
}

// ============================================================
// Canvas 绘制 & 工具函数
// ============================================================

/**
 * 在 Canvas 上绘制所有物体的标注。
 *
 * - 有 polygon：用 Path 画闭合多边形轮廓
 * - 无 polygon：用 drawRect 画矩形框（回退模式）
 * - 选中：蓝色 + 加粗；未选中：红色
 * - 编号圆圈：左上角，半径 14px
 */
private fun DrawScope.drawAnnotations(
    objects: List<DetectedObject>,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    // 注意：每次 draw 创建 Path，物体数量少时 GC 影响可忽略
    for (obj in objects) {
        val color = if (obj.selected) AnnotationBlue else AnnotationRed
        val strokeWidth = if (obj.selected) 3f else 2f

        // bbox 左上角像素坐标（用于编号圆圈定位）
        val px = obj.bbox.x * w
        val py = obj.bbox.y * h

        // 轮廓：多边形 或 矩形框
        if (obj.polygon != null && obj.polygon.size >= 3) {
            val path = Path().apply {
                obj.polygon.forEachIndexed { idx, point ->
                    // 顶点长度校验：跳过无效顶点，防止 IndexOutOfBoundsException
                    if (point.size < 2) return@forEachIndexed
                    val pointX = point[0] * w
                    val pointY = point[1] * h
                    if (idx == 0) moveTo(pointX, pointY) else lineTo(pointX, pointY)
                }
                close()
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = strokeWidth),
            )
        } else {
            val rectW = obj.bbox.w * w
            val rectH = obj.bbox.h * h
            drawRect(
                color = color,
                topLeft = Offset(px, py),
                size = Size(rectW, rectH),
                style = Stroke(width = strokeWidth),
            )
        }

        // 编号圆圈（半径 14px，对齐 image-edit.js）
        drawCircle(
            color = color,
            radius = 14f,
            center = Offset(px, py),
        )
        // 白色边框
        drawCircle(
            color = Color.White,
            radius = 14f,
            center = Offset(px, py),
            style = Stroke(width = 1.5f),
        )
    }
}

/**
 * 射线法判断点 (x, y) 是否在多边形内部。
 *
 * 对齐 image-edit.js 的 pointInPolygon 实现。
 * polygon 为归一化坐标 [[x, y], ...]，0-1。
 *
 * @param x 点 X（归一化 0-1）
 * @param y 点 Y（归一化 0-1）
 * @param polygon 多边形顶点列表 [[x, y], ...]
 * @return true 表示点在多边形内部
 */
private fun pointInPolygon(
    x: Float,
    y: Float,
    polygon: List<List<Float>>,
): Boolean {
    // 顶点长度校验：过滤掉长度不足 2 的无效顶点，防止 IndexOutOfBoundsException
    val safePolygon = polygon.filter { it.size >= 2 }
    if (safePolygon.size < 3) return false
    var inside = false
    val n = safePolygon.size
    for (i in 0 until n) {
        val j = (i + n - 1) % n
        val xi = safePolygon[i][0]
        val yi = safePolygon[i][1]
        val xj = safePolygon[j][0]
        val yj = safePolygon[j][1]
        val intersect = (yi > y) != (yj > y) &&
            x < (xj - xi) * (y - yi) / (yj - yi) + xi
        if (intersect) inside = !inside
    }
    return inside
}
