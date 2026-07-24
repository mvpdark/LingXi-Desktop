package top.mvpdark.lx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import top.mvpdark.lx.ui.theme.Champagne
import top.mvpdark.lx.ui.theme.Obsidian

/**
 * TRAE 风格上传缩略图。
 *
 * 设计规范（对齐 TRAE 风格）：
 * - 尺寸 36×36 dp，6 dp 圆角
 * - 左上角缩略图，不显示文件名
 * - ContentScale.Crop 裁剪填充
 * - 右上角小 × 删除按钮
 * - 点击缩略图预览
 *
 * Noir Aurum 风格：1dp 金色描边（alpha 0.4）+ 金底黑×删除按钮。
 *
 * @param model 图片模型，可为 ByteArray（本地选择）、String（URL / data URL）等 Coil3 支持的类型。
 * @param onPreview 点击缩略图回调（打开全屏预览）。
 * @param onDelete 点击删除按钮回调。
 * @param modifier 修饰符。
 */
@Composable
fun UploadThumbnail(
    model: Any?,
    onPreview: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbnailShape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(thumbnailShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(width = 1.dp, color = Champagne.copy(alpha = 0.4f), shape = thumbnailShape)
            .clickable(onClick = onPreview),
    ) {
        AsyncImage(
            model = model,
            contentDescription = "待发送图片",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // 右上角删除按钮（金色底 + 黑色 × 图标，黑金二元对比）
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(16.dp)
                .clip(CircleShape)
                .background(Champagne)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "删除图片",
                tint = Obsidian,
                modifier = Modifier.size(10.dp),
            )
        }
    }
}
