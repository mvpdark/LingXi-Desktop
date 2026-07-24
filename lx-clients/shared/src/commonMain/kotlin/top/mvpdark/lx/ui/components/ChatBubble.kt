package top.mvpdark.lx.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import top.mvpdark.lx.core.util.UrlResolver
import top.mvpdark.lx.ui.theme.AiBubble
import top.mvpdark.lx.ui.theme.Champagne
import top.mvpdark.lx.ui.theme.ChampagneBright
import top.mvpdark.lx.ui.theme.ChampagneDeep
import top.mvpdark.lx.ui.theme.LxThemeStyle
import top.mvpdark.lx.ui.theme.LocalDarkTheme
import top.mvpdark.lx.ui.theme.LocalThemeStyle
import top.mvpdark.lx.ui.theme.NoirAiBubble
import top.mvpdark.lx.ui.theme.Obsidian
import top.mvpdark.lx.ui.theme.userBubbleBrush

/**
 * 聊天气泡组件。
 *
 * 双风格支持：
 * - NOIR_AURUM（黑曜鎏金）：
 *   - AI 气泡：NoirAiBubble 深黑表面 + 1dp 金色描边（alpha 0.3）+ 20dp 圆角 + 2dp 极淡金阴影
 *   - 用户气泡：userBubbleBrush 金色渐变 + 黑色文字 + 20dp 圆角（黑底金字 vs 金底黑字二元对比）
 *   - 图片：16dp 圆角 + 1dp 金色描边
 *   - 时间戳：ChampagneDeep.copy(alpha = 0.7f) 暗金
 * - QUIET_MATERIALITY（沉静物性）：保持原有奶白/绿渐变逻辑
 *
 * @param text 消息文本。
 * @param isUser 是否为用户消息（false 表示 AI 消息）。
 * @param images 图片 URL 列表（data URL 或 http(s) URL）。
 * @param timestamp 时间戳文本（已格式化）。
 * @param modifier 修饰符。
 * @param onImageClick 点击图片回调（全屏预览）。
 * @param onImageLongClick 长按图片回调（保存图片）。
 * @param onImageSave 点击保存按钮回调（null 表示不显示保存按钮）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(
    text: String,
    isUser: Boolean,
    images: List<String> = emptyList(),
    timestamp: String = "",
    modifier: Modifier = Modifier,
    onImageClick: (String) -> Unit = {},
    onImageLongClick: (String) -> Unit = {},
    onImageSave: ((String) -> Unit)? = null,
) {
    val isDark = LocalDarkTheme.current
    val style = LocalThemeStyle.current
    val isNoirAurum = style == LxThemeStyle.NOIR_AURUM

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            val bubbleShape = RoundedCornerShape(20.dp)

            // 气泡背景画刷：Noir Aurum 用 NoirAiBubble / userBubbleBrush；Quiet Materiality 保持原逻辑
            val bubbleBrush: Brush = when {
                isNoirAurum && isUser -> userBubbleBrush(isDark)
                isNoirAurum && !isUser -> SolidColor(NoirAiBubble)
                isUser -> userBubbleBrush(isDark)
                else -> SolidColor(if (isDark) Color(0xFF2E2E2C) else AiBubble)
            }

            // AI 气泡阴影：Noir Aurum 用 2dp 金色阴影（漆器浮起感），Quiet Materiality 用 1dp 普通阴影
            val bubbleShadowElevation = when {
                !isUser && isNoirAurum -> 2.dp
                !isUser -> 1.dp
                else -> 0.dp
            }

            // 文字颜色：Noir Aurum 用金/黑二元（黑底金字 vs 金底黑字），Quiet Materiality 用白/onSurface
            val textColor: Color = when {
                isNoirAurum && isUser -> Obsidian
                isNoirAurum && !isUser -> ChampagneBright
                isUser -> Color.White
                else -> MaterialTheme.colorScheme.onSurface
            }

            // Noir Aurum AI 气泡：1dp 金色描边（alpha 0.3，莳绘浮起感）
            val goldBorderModifier = if (isNoirAurum && !isUser) {
                Modifier.border(width = 1.dp, color = Champagne.copy(alpha = 0.3f), shape = bubbleShape)
            } else {
                Modifier
            }

            if (text.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .then(if (bubbleShadowElevation > 0.dp) {
                            if (isNoirAurum && !isUser) {
                                // Noir Aurum AI 气泡：金色阴影（漆器莳绘浮起的光泽）
                                Modifier.shadow(
                                    elevation = 2.dp,
                                    shape = bubbleShape,
                                    ambientColor = Champagne.copy(alpha = 0.1f),
                                    spotColor = Champagne.copy(alpha = 0.15f),
                                )
                            } else {
                                Modifier.shadow(elevation = bubbleShadowElevation, shape = bubbleShape)
                            }
                        } else {
                            Modifier
                        })
                        .then(goldBorderModifier)
                        .clip(bubbleShape)
                        .background(brush = bubbleBrush)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor,
                        overflow = TextOverflow.Visible,
                    )
                }
            }

            // 图片内容
            if (images.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    val imageShape = RoundedCornerShape(16.dp)
                    images.forEach { imageUrl ->
                        Box {
                            AsyncImage(
                                model = UrlResolver.resolveImageUrl(imageUrl),
                                contentDescription = "消息图片",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier
                                    .sizeIn(maxWidth = 240.dp, maxHeight = 320.dp)
                                    .combinedClickable(
                                        onClick = { onImageClick(imageUrl) },
                                        onLongClick = { onImageLongClick(imageUrl) },
                                    )
                                    .then(
                                        if (isNoirAurum) {
                                            Modifier.border(width = 1.dp, color = Champagne.copy(alpha = 0.3f), shape = imageShape)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .clip(imageShape),
                            )

                            // 右上角保存按钮覆盖层（半透明黑底 + 白色下载图标）
                            if (onImageSave != null) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.5f))
                                        .clickable { onImageSave(imageUrl) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = "保存图片",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 时间戳：Noir Aurum 用 ChampagneDeep.copy(alpha = 0.7f) 暗金，Quiet Materiality 用 onSurfaceVariant
            if (timestamp.isNotBlank()) {
                val timestampColor = if (isNoirAurum) ChampagneDeep.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    text = timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = timestampColor,
                    modifier = Modifier.padding(top = 4.dp, end = 4.dp, start = 4.dp),
                )
            }
        }
    }
}
