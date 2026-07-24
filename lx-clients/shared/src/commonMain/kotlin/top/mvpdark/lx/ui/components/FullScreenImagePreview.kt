package top.mvpdark.lx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import top.mvpdark.lx.ui.theme.Champagne
import top.mvpdark.lx.ui.theme.Obsidian

/**
 * 全屏图片预览组件。
 *
 * 设计规范：
 * - 全屏覆盖（fillMaxSize），曜石黑背景
 * - ContentScale.Fit（完整显示，不裁剪）
 * - 支持双指缩放（zoom）和拖动（pan）
 * - 点击空白区域关闭
 * - 右上角 × 关闭按钮（金色底 + 黑色图标，Noir Aurum 二元）
 * - 横屏时自动隐藏系统栏（沉浸式全屏），竖屏保持系统栏可见
 *
 * @param model 图片模型，可为 ByteArray、String（URL / data URL）等 Coil3 支持的类型。
 * @param onDismiss 关闭回调。
 * @param onSave 保存回调（null 表示不显示保存按钮）。
 */
@Composable
fun FullScreenImagePreview(
    model: Any?,
    onDismiss: () -> Unit,
    onSave: (() -> Unit)? = null,
) {
    // 缩放与平移状态（zoom / pan）
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 横屏检测（跨平台：比较宽高而非读取 Configuration）
        val isLandscape = maxWidth > maxHeight

        // 横屏时激活沉浸式系统栏
        ImmersiveSystemBarsEffect(active = isLandscape)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Obsidian.copy(alpha = if (isLandscape) 1f else 0.95f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = model,
                contentDescription = "图片预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                            scale = newScale
                            // 仅在放大状态下累计平移；缩放回到 1 时复位偏移
                            offset = if (newScale > 1f) {
                                offset + pan
                            } else {
                                Offset.Zero
                            }
                        }
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )

            // 右上角按钮行：保存按钮（可选）+ 关闭按钮
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 保存按钮（可选）
                    if (onSave != null) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Champagne)
                                .clickable(onClick = onSave),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "保存图片",
                                tint = Obsidian,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    // 关闭按钮
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Champagne)
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = Obsidian,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 缩放范围限制。 */
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f
