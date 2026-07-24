package top.mvpdark.lx.ui.emoji

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import lx_clients.shared.generated.resources.Res
import top.mvpdark.lx.core.util.PlatformLogger

/** APNG 解析数据共享缓存（按资源路径索引，避免同一表情多次解析）。 */
private val apngDataCache = mutableMapOf<String, ApngParser.ApngData>()

/** APNG 合成帧共享缓存（按资源路径索引，避免同一表情多次合成帧）。 */
private val apngFramesCache = mutableMapOf<String, List<ImageBitmap>>()

/**
 * APNG 动画表情组件。
 *
 * 从 Compose Resources 加载 APNG 文件，解析帧数据，在 Compose 中播放动画。
 *
 * 特性：
 * - 纯 Kotlin APNG 解析（无第三方依赖）
 * - 平台原生 PNG 解码（Android: BitmapFactory, Desktop: ImageIO）
 * - 按帧延迟自动播放，支持循环
 * - 加载中显示空白占位
 *
 * @param resourcePath Compose Resources 中的文件路径（如 "files/emoji/animated/idle.apng"）。
 * @param modifier 修饰符。
 * @param size 表情尺寸（正方形）。
 */
@Composable
fun AnimatedEmoji(
    resourcePath: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    // 加载并解析 APNG
    val apngState = produceState<ApngParser.ApngData?>(initialValue = null, resourcePath) {
        value = withContext(Dispatchers.Default) {
            try {
                // 先查共享缓存，避免同一表情多次解析
                apngDataCache[resourcePath] ?: run {
                    val bytes = Res.readBytes(resourcePath)
                    ApngParser().parse(bytes).also { apngDataCache[resourcePath] = it }
                }
            } catch (e: Exception) {
                PlatformLogger.e("AnimatedEmoji", "APNG 解析失败: $resourcePath", e)
                null
            }
        }
    }

    val apngData = apngState.value

    if (apngData == null || apngData.frames.isEmpty()) {
        // 加载中或解析失败：显示空白占位
        Box(modifier = modifier.size(size))
        return
    }

    // 解码并按 fcTL 参数把所有帧合成到完整画布（在 Default 线程）
    // 之前的实现直接拉伸显示每帧原始位图，忽略 xOffset/yOffset/blend/dispose，
    // 对部分帧（局部更新）的 APNG 会渲染错位/花屏，此处按规范逐帧合成。
    val framesState = produceState<List<ImageBitmap>?>(initialValue = null, apngData) {
        value = withContext(Dispatchers.Default) {
            try {
                // 先查共享缓存，避免同一表情多次合成帧
                apngFramesCache[resourcePath] ?: run {
                    compositeApngFrames(apngData).also { apngFramesCache[resourcePath] = it }
                }
            } catch (e: Exception) {
                PlatformLogger.e("AnimatedEmoji", "帧合成失败", e)
                null
            }
        }
    }

    val frames = framesState.value
    if (frames.isNullOrEmpty()) {
        Box(modifier = modifier.size(size))
        return
    }

    // 单帧：直接显示
    if (frames.size == 1) {
        Image(
            bitmap = frames[0],
            contentDescription = null,
            modifier = modifier.size(size),
            contentScale = ContentScale.Fit,
        )
        return
    }

    // 多帧：动画播放
    var currentFrame by remember(resourcePath) { mutableIntStateOf(0) }

    LaunchedEffect(resourcePath, frames.size) {
        // numPlays = 0 表示无限循环；> 0 时播放指定次数后停在最后一帧（APNG 规范）
        val maxLoops = if (apngData.numPlays <= 0) Int.MAX_VALUE else apngData.numPlays
        var loop = 0
        while (loop < maxLoops) {
            for (index in frames.indices) {
                currentFrame = index
                val frameDelay = apngData.frames.getOrNull(index)?.delayMs ?: 100
                // 最小延迟 50ms，防止过快闪烁
                delay(frameDelay.coerceAtLeast(50))
            }
            loop++
        }
        // 播放完毕：currentFrame 停留在最后一帧
    }

    Image(
        bitmap = frames[currentFrame],
        contentDescription = null,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}

/**
 * 按 APNG 规范将各帧合成到完整画布。
 *
 * 逐帧处理 fcTL 参数：
 * - 按 xOffset/yOffset 把帧位图画到画布对应位置（不拉伸到全画布）
 * - blend=SOURCE 覆盖目标区域（BlendMode.Src），blend=OVER 做 alpha 叠加（BlendMode.SrcOver）
 * - dispose=BACKGROUND 在下一帧前清空帧区域，dispose=PREVIOUS 恢复上一帧画布
 *
 * @return 每帧合成后的完整画布快照（尺寸均为 APNG 画布尺寸）。
 */
private fun compositeApngFrames(apngData: ApngParser.ApngData): List<ImageBitmap> {
    val canvasWidth = apngData.width
    val canvasHeight = apngData.height
    if (canvasWidth <= 0 || canvasHeight <= 0) return emptyList()

    val canvasBitmap = ImageBitmap(canvasWidth, canvasHeight)
    val canvas = Canvas(canvasBitmap)
    val snapshots = mutableListOf<ImageBitmap>()
    var previousCanvas: ImageBitmap? = null

    for (frame in apngData.frames) {
        // dispose=PREVIOUS 需要先保存当前画布，绘制后恢复
        if (frame.disposeOp == ApngParser.DisposeOp.PREVIOUS) {
            previousCanvas = ImageBitmap(canvasWidth, canvasHeight).also { copy ->
                Canvas(copy).drawImage(canvasBitmap, Offset.Zero, Paint())
            }
        }

        val frameBitmap = try {
            decodePngBytes(frame.pngBytes)
        } catch (e: Exception) {
            PlatformLogger.e("AnimatedEmoji", "帧解码失败", e)
            null
        }
        if (frameBitmap != null) {
            val paint = Paint().apply {
                blendMode = if (frame.blendOp == ApngParser.BlendOp.SOURCE) {
                    BlendMode.Src
                } else {
                    BlendMode.SrcOver
                }
            }
            canvas.drawImageRect(
                image = frameBitmap,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(frameBitmap.width, frameBitmap.height),
                dstOffset = IntOffset(frame.xOffset, frame.yOffset),
                dstSize = IntSize(frame.width, frame.height),
                paint = paint,
            )
        }

        // 快照当前画布作为展示帧
        snapshots += ImageBitmap(canvasWidth, canvasHeight).also { snapshot ->
            Canvas(snapshot).drawImage(canvasBitmap, Offset.Zero, Paint())
        }

        // 应用 dispose 操作，为下一帧准备画布
        when (frame.disposeOp) {
            ApngParser.DisposeOp.BACKGROUND -> {
                canvas.drawRect(
                    left = frame.xOffset.toFloat(),
                    top = frame.yOffset.toFloat(),
                    right = (frame.xOffset + frame.width).toFloat(),
                    bottom = (frame.yOffset + frame.height).toFloat(),
                    paint = Paint().apply { blendMode = BlendMode.Clear },
                )
            }
            ApngParser.DisposeOp.PREVIOUS -> {
                previousCanvas?.let { canvas.drawImage(it, Offset.Zero, Paint()) }
            }
            // DisposeOp.NONE：保留画布，无需处理
        }
    }

    return snapshots
}

/**
 * APNG 表情占位符（静态，不加载资源）。
 *
 * 用于加载前的占位显示。
 */
@Composable
fun EmojiPlaceholder(
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    Box(modifier = modifier.size(size))
}
