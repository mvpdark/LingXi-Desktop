package top.mvpdark.lx.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 图像编辑模块数据模型。
 *
 * 对齐后端 /api/upload、/api/vlm-detect、/api/image-edit[-annotated] 接口，
 * 以及前端 image-edit.js 的字段约定。字段命名与后端 JSON 保持一致（snake_case），
 * 通过 kotlinx.serialization 的 @SerialName 注解映射到 Kotlin 属性。
 */

/** 归一化边界框（坐标 0~1）。对应后端 bbox: {x, y, w, h}。 */
@Serializable
data class Bbox(
    val x: Float = 0f,
    val y: Float = 0f,
    val w: Float = 0f,
    val h: Float = 0f,
)

/** VLM 检测到的物体。 */
@Serializable
data class DetectedObject(
    val id: Int = 0,
    val label: String = "",
    val bbox: Bbox = Bbox(),
    /** 归一化多边形轮廓 [[x,y], ...]，VLM 未返回时为 null。 */
    val polygon: List<List<Float>>? = null,
    /** 像素级 mask（base64 PNG），VLM 未返回时为 null。 */
    @SerialName("mask_png_b64") val maskPngB64: String? = null,
    /** UI 选中状态（纯前端字段，后端不返回，反序列化时用默认值 false）。 */
    val selected: Boolean = false,
)

/** 发给后端的 region 格式（对齐 image-edit.js startEdit）。 */
@Serializable
data class EditRegion(
    val id: Int = 0,
    val label: String = "",
    val bbox: Bbox = Bbox(),
    val polygon: List<List<Float>>? = null,
    @SerialName("mask_png_b64") val maskPngB64: String? = null,
)

/** /api/vlm-detect 响应。 */
@Serializable
data class VlmDetectResponse(
    val success: Boolean = false,
    val objects: List<VlmObject> = emptyList(),
    val error: String = "",
)

/** VLM 检测返回的单个物体（轻量结构，无 polygon/mask）。 */
@Serializable
data class VlmObject(
    val id: Int? = null,
    val label: String = "",
    val bbox: Bbox? = null,
)

/** /api/upload 响应。 */
@Serializable
data class UploadResponse(
    val success: Boolean = false,
    /** data:image/...;base64,... */
    val image: String = "",
    val id: String? = null,
    val url: String = "",
    val error: String = "",
)

/** /api/image-edit[-annotated] 响应。 */
@Serializable
data class ImageEditResponse(
    val success: Boolean = false,
    val image: String = "",
    val url: String = "",
    val error: String = "",
)
