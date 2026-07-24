package top.mvpdark.lx.data.repository

import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.FormBuilder
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.encodeToString
import top.mvpdark.lx.core.network.ApiClient
import top.mvpdark.lx.core.util.PlatformLogger
import top.mvpdark.lx.core.util.sanitizeMultipartFileName
import top.mvpdark.lx.core.util.toUserMessage
import top.mvpdark.lx.data.model.DetectedObject
import top.mvpdark.lx.data.model.EditRegion
import top.mvpdark.lx.data.model.ImageEditResponse
import top.mvpdark.lx.data.model.UploadResponse
import top.mvpdark.lx.data.model.VlmDetectResponse
import top.mvpdark.lx.data.model.Bbox
import top.mvpdark.lx.data.model.PresetListResponse
import top.mvpdark.lx.data.model.CollaborativeEditResponse
import top.mvpdark.lx.data.model.StyleTransferResponse
import io.ktor.client.request.get

/**
 * 图像编辑仓库：封装 /api/upload、/api/vlm-detect、/api/image-edit[-annotated] 接口。
 *
 * 鉴权策略：
 * - 收到 401 时自动用 refresh_token 刷新并重试（限 1 次）
 * - 刷新失败返回"登录已过期，请重新登录"错误
 * - 所有 HTTP 错误码本地化为中文文案，不泄露后端英文错误
 *
 * 超时策略：
 * - 普通上传 [/api/upload]：30 秒
 * - VLM 检测 [/api/vlm-detect]：90 秒
 * - 图生图 [/api/image-edit[-annotated]]：120 秒
 */
class ImageEditRepository(
    private val apiClient: ApiClient,
) {
    /**
     * 上传图片。POST /api/upload，multipart 字段：file。
     */
    suspend fun uploadImage(bytes: ByteArray, fileName: String): UploadResponse {
        return requestWithAuthRetry(
            onError = { UploadResponse(success = false, error = it) },
        ) {
            apiClient.httpClient.submitFormWithBinaryData(
                url = "/api/upload",
                formData = formData { appendFile("file", bytes, fileName) },
            )
        }.let { resp ->
            // 容错：后端旧版本不返回 success，以 image 非空为成功依据
            if (resp.image.isNotEmpty() && !resp.success) resp.copy(success = true) else resp
        }
    }

    /**
     * VLM 物体检测。POST /api/vlm-detect。超时 90 秒。
     */
    suspend fun vlmDetect(bytes: ByteArray, fileName: String): VlmDetectResponse {
        return requestWithAuthRetry(
            onError = { VlmDetectResponse(success = false, error = it) },
        ) {
            apiClient.httpClient.submitFormWithBinaryData(
                url = "/api/vlm-detect",
                formData = formData { appendFile("file", bytes, fileName) },
            ) {
                timeout {
                    requestTimeoutMillis = 90_000
                    socketTimeoutMillis = 90_000
                }
            }
        }
    }

    /**
     * 直接图生图编辑。POST /api/image-edit。超时 120 秒。
     */
    suspend fun editImage(
        bytes: ByteArray,
        fileName: String,
        prompt: String,
        resolution: String = "1K",
        ratio: String = "1:1",
    ): ImageEditResponse {
        return requestWithAuthRetry(
            onError = { ImageEditResponse(success = false, error = it) },
        ) {
            apiClient.httpClient.submitFormWithBinaryData(
                url = "/api/image-edit",
                formData = formData {
                    appendFile("file", bytes, fileName)
                    append("prompt", prompt)
                    append("resolution", resolution)
                    append("ratio", ratio)
                },
            ) {
                timeout {
                    requestTimeoutMillis = 120_000
                    socketTimeoutMillis = 120_000
                }
            }
        }
    }

    /**
     * 带区域标注的图生图编辑。POST /api/image-edit-annotated。超时 120 秒。
     */
    suspend fun editImageAnnotated(
        bytes: ByteArray,
        fileName: String,
        prompt: String,
        regions: List<DetectedObject>,
        resolution: String = "1K",
        ratio: String = "1:1",
    ): ImageEditResponse {
        val editRegions = regions.map { obj ->
            EditRegion(
                id = obj.id,
                label = obj.label,
                bbox = obj.bbox,
                polygon = obj.polygon,
                maskPngB64 = obj.maskPngB64,
            )
        }
        val regionsJson = apiClient.json.encodeToString(editRegions)

        return requestWithAuthRetry(
            onError = { ImageEditResponse(success = false, error = it) },
        ) {
            apiClient.httpClient.submitFormWithBinaryData(
                url = "/api/image-edit-annotated",
                formData = formData {
                    appendFile("file", bytes, fileName)
                    append("prompt", prompt)
                    append("regions", regionsJson)
                    append("resolution", resolution)
                    append("ratio", ratio)
                },
            ) {
                timeout {
                    requestTimeoutMillis = 120_000
                    socketTimeoutMillis = 120_000
                }
            }
        }
    }

    /**
     * 去除图片背景。POST /api/rembg-remove。超时 60 秒。
     */
    suspend fun removeBackground(bytes: ByteArray, fileName: String): ImageEditResponse {
        return requestWithAuthRetry(
            onError = { ImageEditResponse(success = false, error = it) },
        ) {
            apiClient.httpClient.submitFormWithBinaryData(
                url = "/api/rembg-remove",
                formData = formData {
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentType, "image/jpeg")
                        append(HttpHeaders.ContentDisposition, "filename=$fileName")
                    })
                    append("alpha_matting", "false")
                }
            )
        }
    }

    /**
     * 图片增强（超分辨率）。POST /api/enhance。超时 120 秒。
     */
    suspend fun enhanceImage(
        bytes: ByteArray,
        fileName: String,
        mode: String = "super_resolution",
        scale: Int = 2,
    ): ImageEditResponse {
        return requestWithAuthRetry(
            onError = { ImageEditResponse(success = false, error = it) },
        ) {
            apiClient.httpClient.submitFormWithBinaryData(
                url = "/api/enhance",
                formData = formData {
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentType, "image/jpeg")
                        append(HttpHeaders.ContentDisposition, "filename=$fileName")
                    })
                    append("mode", mode)
                    append("scale", scale.toString())
                }
            )
        }
    }

    /**
     * 导出图片。POST /api/export。
     *
     * @param bytes 图片字节流
     * @param fileName 文件名
     * @param format 导出格式（jpeg / png / webp）
     * @param quality 导出质量（10-100）
     */
    suspend fun exportImage(
        bytes: ByteArray,
        fileName: String,
        format: String,
        quality: Int,
    ): ImageEditResponse {
        return requestWithAuthRetry(
            onError = { ImageEditResponse(success = false, error = it) },
        ) {
            apiClient.httpClient.submitFormWithBinaryData(
                url = "/api/export",
                formData = formData {
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentType, "image/jpeg")
                        append(HttpHeaders.ContentDisposition, "filename=$fileName")
                    })
                    append("format", format)
                    append("quality", quality.toString())
                }
            )
        }
    }


    /**
     * 获取修图预设列表。GET /api/presets。
     */
    suspend fun getPresets(): PresetListResponse {
        return requestWithAuthRetry(
            onError = { PresetListResponse(success = false, presets = emptyList()) },
        ) {
            apiClient.httpClient.get("/api/presets")
        }
    }

    /**
     * 应用修图预设。POST /api/preset-apply。超时 120 秒。
     */
    suspend fun applyPreset(
        bytes: ByteArray,
        fileName: String,
        presetId: String,
    ): ImageEditResponse {
        return requestWithAuthRetry(
            onError = { ImageEditResponse(success = false, error = it) },
        ) {
            apiClient.httpClient.submitFormWithBinaryData(
                url = "/api/preset-apply",
                formData = formData {
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentType, "image/jpeg")
                        append(HttpHeaders.ContentDisposition, "filename=$fileName")
                    })
                    append("preset_id", presetId)
                }
            ) {
                timeout {
                    requestTimeoutMillis = 120_000
                    socketTimeoutMillis = 120_000
                }
            }
        }
    }

    /**
     * 多Agent协作修图。POST /api/collaborative-edit。超时 180 秒。
     */
    suspend fun collaborativeEdit(bytes: ByteArray, fileName: String): CollaborativeEditResponse {
        return requestWithAuthRetry(
            onError = { CollaborativeEditResponse(success = false, error = it) },
        ) {
            apiClient.httpClient.submitFormWithBinaryData(
                url = "/api/collaborative-edit",
                formData = formData {
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentType, "image/jpeg")
                        append(HttpHeaders.ContentDisposition, "filename=$fileName")
                    })
                }
            ) {
                timeout {
                    requestTimeoutMillis = 180_000
                    socketTimeoutMillis = 180_000
                }
            }
        }
    }

    /**
     * 跨照片风格迁移。POST /api/style-transfer。超时 180 秒。
     * 需要 target 和 reference 两个文件字段。
     */
    suspend fun styleTransfer(
        targetBytes: ByteArray, targetFileName: String,
        referenceBytes: ByteArray, referenceFileName: String,
    ): StyleTransferResponse {
        return requestWithAuthRetry(
            onError = { StyleTransferResponse(success = false, error = it) },
        ) {
            apiClient.httpClient.submitFormWithBinaryData(
                url = "/api/style-transfer",
                formData = formData {
                    append("target", targetBytes, Headers.build {
                        append(HttpHeaders.ContentType, "image/jpeg")
                        append(HttpHeaders.ContentDisposition, "filename=$targetFileName")
                    })
                    append("reference", referenceBytes, Headers.build {
                        append(HttpHeaders.ContentType, "image/jpeg")
                        append(HttpHeaders.ContentDisposition, "filename=$referenceFileName")
                    })
                }
            ) {
                timeout {
                    requestTimeoutMillis = 180_000
                    socketTimeoutMillis = 180_000
                }
            }
        }
    }

    /**
     * 带鉴权重试的请求包装器。
     *
     * 流程：
     * 1. 发起请求
     * 2. 如果 401 → 用 refresh_token 刷新 → 重试一次
     * 3. 刷新失败 → 返回"登录已过期"错误
     * 4. 其他错误 → 返回本地化错误
     * 5. 成功 → 反序列化响应
     *
     * @param onError 错误回调，接收本地化错误文案，返回带 error 字段的默认对象
     * @param block 请求执行块
     */
    private suspend inline fun <reified T> requestWithAuthRetry(
        noinline onError: (String) -> T,
        noinline block: suspend () -> HttpResponse,
    ): T {
        // 第一次尝试
        val firstResponse = try {
            block()
        } catch (e: Exception) {
            PlatformLogger.e("ImageEditRepository", "Request failed", e)
            return onError(e.toUserMessage())
        }

        // 成功：反序列化
        if (firstResponse.status.isSuccess()) {
            return try {
                firstResponse.body<T>()
            } catch (e: Exception) {
                PlatformLogger.e("ImageEditRepository", "Parse failed", e)
                onError(e.toUserMessage())
            }
        }

        // 401：尝试刷新 token 并重试
        if (firstResponse.status.value == 401) {
            val refreshed = apiClient.ensureValidToken()
            if (refreshed) {
                // 重试一次
                val retryResponse = try {
                    block()
                } catch (e: Exception) {
                    PlatformLogger.e("ImageEditRepository", "Retry failed", e)
                    return onError(e.toUserMessage())
                }
                if (retryResponse.status.isSuccess()) {
                    return try {
                        retryResponse.body<T>()
                    } catch (e: Exception) {
                        onError(e.toUserMessage())
                    }
                }
                return onError(localizeHttpError(retryResponse.status.value))
            } else {
                return onError("登录已过期，请重新登录")
            }
        }

        // 其他 HTTP 错误
        return onError(localizeHttpError(firstResponse.status.value))
    }

    /** HTTP 状态码 → 中文文案。 */
    private fun localizeHttpError(statusCode: Int): String = when (statusCode) {
        401 -> "登录已过期，请重新登录"
        403 -> "无权限访问"
        404 -> "请求的资源不存在"
        413 -> "图片过大，请压缩后重试"
        415 -> "不支持的图片格式"
        429 -> "操作过于频繁，请稍后再试"
        in 500..599 -> "服务器开小差了，请稍后重试"
        else -> "请求失败（$statusCode）"
    }

    private fun FormBuilder.appendFile(
        name: String,
        bytes: ByteArray,
        fileName: String,
    ) {
        val safeFileName = sanitizeMultipartFileName(fileName)
        append(name, bytes, Headers.build {
            append(HttpHeaders.ContentDisposition, "form-data; name=\"$name\"; filename=\"$safeFileName\"")
            append(HttpHeaders.ContentType, guessContentType(safeFileName))
        })
    }

    private fun guessContentType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
    }
}
