package top.mvpdark.lx.ui.imageedit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberImagePickerLauncher(onResult: (ByteArray?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            // 将 IO 密集的读取操作切到 Dispatchers.IO，避免阻塞主线程
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { readBytesFromUri(context, uri) }
                onResult(bytes)
            }
        } else {
            onResult(null)
        }
    }
    return remember(launcher) {
        { launcher.launch("image/*") }
    }
}

private fun readBytesFromUri(context: Context, uri: Uri): ByteArray? {
    return runCatching {
        // 读取原始字节流
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@runCatching null

        // 读取 EXIF orientation（与 Coil AsyncImage 的显示保持一致）。
        // 注意：ExifInterface 需要独立的输入流，不能用已读完的 rawBytes。
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        // 无需旋转：直接返回原始字节，保留原图编码格式与质量
        if (orientation == ExifInterface.ORIENTATION_NORMAL) {
            return@runCatching rawBytes
        }

        // 先用 inJustDecodeBounds 获取尺寸（不加载像素到内存），
        // 再按目标分辨率下采样解码，避免大图（如 48MP 照片）直接解码导致 OOM
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOptions)
        val targetMaxDim = 2048  // 与后端压缩限制一致
        val sampleSize = calculateSampleSize(boundsOptions.outWidth, boundsOptions.outHeight, targetMaxDim)
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
            ?: return@runCatching rawBytes
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
        }
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true,
        )
        if (rotated != bitmap) {
            bitmap.recycle()
        }
        // 重新编码为 JPEG（剥离 EXIF，保证后端解码与 Coil 显示坐标系一致）
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 95, out)
        rotated.recycle()
        out.toByteArray()
    }.onFailure { e ->
        top.mvpdark.lx.core.util.PlatformLogger.e(
            "ImagePicker",
            "Failed to read image from URI: $uri",
            e,
        )
    }.getOrNull()
}

/**
 * 计算下采样率，使解码后图片最长边不超过 [targetMaxDim]。
 *
 * @param width 原始图片宽度（来自 inJustDecodeBounds）
 * @param height 原始图片高度
 * @param targetMaxDim 目标最大边长（像素），默认 2048
 * @return inSampleSize 值（2 的幂次）
 */
private fun calculateSampleSize(width: Int, height: Int, targetMaxDim: Int): Int {
    var sampleSize = 1
    val maxDim = maxOf(width, height)
    while (maxDim / sampleSize > targetMaxDim) {
        sampleSize *= 2
    }
    return sampleSize
}
