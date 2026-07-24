package top.mvpdark.lx.ui.emoji

/**
 * 纯 Kotlin APNG 格式解析器。
 *
 * APNG (Animated PNG) 是 PNG 的扩展，通过额外的 chunk 实现动画：
 * - acTL (Animation Control): 帧数、播放次数
 * - fcTL (Frame Control): 每帧的尺寸、偏移、延迟、dispose/blend 操作
 * - fdAT (Frame Data): 帧图像数据（与 IDAT 格式相同，前缀 4 字节序号）
 *
 * 解析策略：将每帧的 fdAT 数据转换为独立的 PNG 字节流，
 * 交由平台解码器（Android BitmapFactory / Desktop ImageIO）解码。
 *
 * 参考：https://wiki.mozilla.org/APNG_Specification
 */
class ApngParser {

    /** 解析后的 APNG 数据。 */
    data class ApngData(
        val width: Int,
        val height: Int,
        val frames: List<ApngFrame>,
        /** 播放次数，0 表示无限循环。 */
        val numPlays: Int,
    )

    /** 单帧数据。 */
    data class ApngFrame(
        val width: Int,
        val height: Int,
        val xOffset: Int,
        val yOffset: Int,
        /** 帧延迟（毫秒）。 */
        val delayMs: Long,
        /** dispose 操作：0=none, 1=background, 2=previous。 */
        val disposeOp: Int,
        /** blend 操作：0=source, 1=over。 */
        val blendOp: Int,
        /** 完整的 PNG 字节流，可直接用平台解码器解码。 */
        val pngBytes: ByteArray,
    )

    /** PNG 文件签名。 */
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** dispose 操作常量。 */
    object DisposeOp {
        const val NONE = 0
        const val BACKGROUND = 1
        const val PREVIOUS = 2
    }

    /** blend 操作常量。 */
    object BlendOp {
        const val SOURCE = 0
        const val OVER = 1
    }

    /**
     * 解析 APNG 字节流。
     *
     * @param data APNG 文件的完整字节流。
     * @return 解析后的 APNG 数据，包含所有帧。
     */
    fun parse(data: ByteArray): ApngData {
        // 验证 PNG 签名
        if (data.size < 8 || !data.take(8).toByteArray().contentEquals(PNG_SIGNATURE)) {
            throw IllegalArgumentException("Invalid PNG signature")
        }

        // 解析所有 chunk
        val chunks = parseChunks(data)
        if (chunks.isEmpty()) {
            throw IllegalArgumentException("No chunks found")
        }

        // 提取 IHDR
        val ihdrChunk = chunks.firstOrNull { it.type == "IHDR" }
            ?: throw IllegalArgumentException("Missing IHDR chunk")
        val (width, height) = parseIhdr(ihdrChunk.data)

        // 提取 acTL（如果不存在，说明是静态 PNG，作为单帧处理）
        val actlChunk = chunks.firstOrNull { it.type == "acTL" }
        if (actlChunk == null) {
            // 静态 PNG：将整个文件作为单帧
            return ApngData(
                width = width,
                height = height,
                frames = listOf(
                    ApngFrame(
                        width = width,
                        height = height,
                        xOffset = 0,
                        yOffset = 0,
                        delayMs = 0,
                        disposeOp = DisposeOp.NONE,
                        blendOp = BlendOp.SOURCE,
                        pngBytes = data,
                    )
                ),
                numPlays = 0,
            )
        }

        val (numFrames, numPlays) = parseActl(actlChunk.data)

        // 收集需要在每帧 PNG 中包含的公共 chunk（IHDR 之后的、IDAT 之前的辅助 chunk）
        // 例如 PLTE, tRNS, gAMA, sRGB, cHRM 等
        val ancillaryChunks = collectAncillaryChunks(chunks)

        // 提取原始 IHDR 字节（包含 length + type + data + CRC）
        val ihdrRaw = buildRawChunk(ihdrChunk)

        // 解析帧
        val frames = mutableListOf<ApngFrame>()
        // 在第一个 fcTL 之前的 IDAT（静态 fallback / 默认图像）
        val defaultIdatChunks = mutableListOf<Chunk>()
        // 当 fcTL 出现在 IDAT 之前时（APNG 第一帧标准格式），
        // IDAT 是当前帧的图像数据，需要累积起来
        val currentFrameIdatChunks = mutableListOf<Chunk>()
        // 累积同一帧的多个 fdAT chunk 数据（每个已跳过 4 字节序号）
        // APNG 规范允许一帧的图像数据分成多个 fdAT chunk（每个约 64KB），
        // 必须在遇到下一个 fcTL 或 IEND 时才合并构建帧
        val currentFrameFdatChunks = mutableListOf<ByteArray>()

        var i = 0
        var currentFctl: Chunk? = null

        while (i < chunks.size) {
            val chunk = chunks[i]
            when (chunk.type) {
                "fcTL" -> {
                    // 遇到新的 fcTL：如果之前有 fcTL 且累积了帧数据，先构建那一帧
                    if (currentFctl != null) {
                        if (currentFrameFdatChunks.isNotEmpty()) {
                            // fdAT 帧：合并所有 fdAT 分片数据构建帧
                            val frame = buildFrame(
                                fctl = currentFctl,
                                idatData = mergeFdatChunks(currentFrameFdatChunks),
                                width = width,
                                height = height,
                                ihdrRaw = ihdrRaw,
                                ancillaryChunks = ancillaryChunks,
                            )
                            frames.add(frame)
                            currentFrameFdatChunks.clear()
                        } else if (currentFrameIdatChunks.isNotEmpty()) {
                            // IDAT 帧（fcTL 在 IDAT 之前的情况）
                            val frame = buildFrameFromIdat(
                                fctl = currentFctl,
                                idatChunks = currentFrameIdatChunks.toList(),
                                width = width,
                                height = height,
                                ihdrRaw = ihdrRaw,
                                ancillaryChunks = ancillaryChunks,
                            )
                            frames.add(frame)
                            currentFrameIdatChunks.clear()
                        }
                    }
                    currentFctl = chunk
                }
                "IDAT" -> {
                    if (currentFctl == null) {
                        // 默认帧的图像数据（在第一个 fcTL 之前的 IDAT，静态 fallback）
                        defaultIdatChunks.add(chunk)
                    } else {
                        // fcTL 在 IDAT 之前：IDAT 是当前帧的图像数据（APNG 第一帧标准格式）
                        // 之前会被错误丢弃，现在累积起来在后续构建帧
                        currentFrameIdatChunks.add(chunk)
                    }
                }
                "fdAT" -> {
                    if (currentFctl != null) {
                        // 提取 fdAT chunk 数据（跳过前 4 字节序号），累积到 currentFrameFdatChunks
                        // 不立即构建帧，等待下一个 fcTL 或 IEND 触发合并
                        val idatData = if (chunk.data.size > 4) {
                            chunk.data.copyOfRange(4, chunk.data.size)
                        } else {
                            ByteArray(0)
                        }
                        currentFrameFdatChunks.add(idatData)
                    }
                }
                "IEND" -> {
                    // IEND：如果当前帧累积了 fdAT 数据，构建最后一帧
                    if (currentFctl != null && currentFrameFdatChunks.isNotEmpty()) {
                        val frame = buildFrame(
                            fctl = currentFctl,
                            idatData = mergeFdatChunks(currentFrameFdatChunks),
                            width = width,
                            height = height,
                            ihdrRaw = ihdrRaw,
                            ancillaryChunks = ancillaryChunks,
                        )
                        frames.add(frame)
                        currentFrameFdatChunks.clear()
                        currentFctl = null
                    }
                }
            }
            i++
        }

        // 循环结束后，处理可能残留的最后一帧
        // （fcTL 在 IDAT 之前，且 IDAT 是最后一帧，没有后续 fcTL/fdAT 触发构建）
        if (currentFctl != null) {
            if (currentFrameFdatChunks.isNotEmpty()) {
                // 残留的 fdAT 帧（正常情况下 IEND 已处理，此处为防御性兜底）
                val frame = buildFrame(
                    fctl = currentFctl,
                    idatData = mergeFdatChunks(currentFrameFdatChunks),
                    width = width,
                    height = height,
                    ihdrRaw = ihdrRaw,
                    ancillaryChunks = ancillaryChunks,
                )
                frames.add(frame)
                currentFrameFdatChunks.clear()
            } else if (currentFrameIdatChunks.isNotEmpty()) {
                val frame = buildFrameFromIdat(
                    fctl = currentFctl,
                    idatChunks = currentFrameIdatChunks.toList(),
                    width = width,
                    height = height,
                    ihdrRaw = ihdrRaw,
                    ancillaryChunks = ancillaryChunks,
                )
                frames.add(frame)
                currentFrameIdatChunks.clear()
            }
        }

        // 处理默认帧（IDAT 在第一个 fcTL 之前，是静态 fallback）
        // APNG 规范：如果第一个 fcTL 出现在 IDAT 之后，则 IDAT 是默认图像，
        // 仅供不支持动画的解码器显示，不属于动画序列，不能插入帧列表。
        // 仅当没有解析出任何动画帧时才用默认帧兜底，保证有内容可显示。
        // 注意：fcTL 在 IDAT 之前时，IDAT 已被 currentFrameIdatChunks 处理，
        // 不会进入 defaultIdatChunks，所以这里无需再判断 firstFctlIndex < firstIdatIndex
        if (defaultIdatChunks.isNotEmpty() && frames.isEmpty()) {
            val defaultFrame = buildDefaultFrame(
                idatChunks = defaultIdatChunks,
                width = width,
                height = height,
                ihdrRaw = ihdrRaw,
                ancillaryChunks = ancillaryChunks,
            )
            frames.add(defaultFrame)
        }

        return ApngData(
            width = width,
            height = height,
            frames = frames,
            numPlays = numPlays,
        )
    }

    // ============================================================
    // Chunk 解析
    // ============================================================

    private data class Chunk(
        val type: String,
        val data: ByteArray,
        val offset: Int,
        val totalLength: Int,
    )

    /**
     * 解析 PNG chunk 列表。
     */
    private fun parseChunks(data: ByteArray): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var pos = 8 // 跳过 PNG 签名

        while (pos < data.size) {
            if (pos + 8 > data.size) break

            val length = readInt32(data, pos)
            val type = String(data, pos + 4, 4, Charsets.US_ASCII)

            if (pos + 12 + length > data.size) break // 数据不完整

            val chunkData = data.copyOfRange(pos + 8, pos + 8 + length)
            chunks.add(Chunk(type, chunkData, pos, 12 + length))

            pos += 12 + length // length(4) + type(4) + data(length) + crc(4)

            if (type == "IEND") break
        }

        return chunks
    }

    /**
     * 解析 IHDR chunk 数据。
     * 返回 (width, height)。
     */
    private fun parseIhdr(data: ByteArray): Pair<Int, Int> {
        require(data.size >= 8) { "IHDR chunk 数据长度不足: ${data.size}" }
        val width = readInt32(data, 0)
        val height = readInt32(data, 4)
        return Pair(width, height)
    }

    /**
     * 解析 acTL chunk 数据。
     * 返回 (num_frames, num_plays)。
     */
    private fun parseActl(data: ByteArray): Pair<Int, Int> {
        require(data.size >= 8) { "acTL chunk 数据长度不足: ${data.size}" }
        val numFrames = readInt32(data, 0)
        val numPlays = readInt32(data, 4)
        return Pair(numFrames, numPlays)
    }

    /**
     * 解析 fcTL chunk 数据。
     */
    private data class FrameControl(
        val width: Int,
        val height: Int,
        val xOffset: Int,
        val yOffset: Int,
        val delayNum: Int,
        val delayDen: Int,
        val disposeOp: Int,
        val blendOp: Int,
    )

    private fun parseFctl(data: ByteArray): FrameControl {
        // fcTL chunk data 固定 26 字节（含 4 字节帧序号），过短说明文件损坏
        require(data.size >= 26) { "fcTL chunk 数据长度不足: ${data.size}" }
        // fcTL chunk data 格式（APNG 规范）：
        // offset 0:  sequence_number (4 bytes) — 帧序号，必须跳过
        // offset 4:  width (4 bytes)
        // offset 8:  height (4 bytes)
        // offset 12: x_offset (4 bytes)
        // offset 16: y_offset (4 bytes)
        // offset 20: delay_num (2 bytes)
        // offset 22: delay_den (2 bytes)
        // offset 24: dispose_op (1 byte)
        // offset 25: blend_op (1 byte)
        return FrameControl(
            width = readInt32(data, 4),
            height = readInt32(data, 8),
            xOffset = readInt32(data, 12),
            yOffset = readInt32(data, 16),
            delayNum = readInt16(data, 20),
            delayDen = readInt16(data, 22),
            disposeOp = data[24].toInt() and 0xFF,
            blendOp = data[25].toInt() and 0xFF,
        )
    }

    // ============================================================
    // PNG 帧构建
    // ============================================================

    /**
     * 收集需要在每帧 PNG 中包含的辅助 chunk。
     * 包括 PLTE, tRNS, gAMA, sRGB, cHRM, iCCP 等（在 IHDR 之后、IDAT 之前）。
     */
    private fun collectAncillaryChunks(chunks: List<Chunk>): List<ByteArray> {
        val validTypes = setOf("PLTE", "tRNS", "gAMA", "sRGB", "cHRM", "iCCP", "sBIT", "bKGD", "hIST", "tEXt", "zTXt", "iTXt")
        val result = mutableListOf<ByteArray>()

        var seenIhdr = false
        for (chunk in chunks) {
            if (chunk.type == "IHDR") {
                seenIhdr = true
                continue
            }
            if (!seenIhdr) continue
            if (chunk.type == "IDAT" || chunk.type == "acTL" || chunk.type == "fcTL" || chunk.type == "fdAT" || chunk.type == "IEND") {
                break
            }
            if (chunk.type in validTypes) {
                result.add(buildRawChunk(chunk))
            }
        }
        return result
    }

    /**
     * 合并同一帧的多个 fdAT chunk 数据。
     *
     * 每个分片在累积时已剥离 4 字节序号前缀，此处仅做字节拼接，
     * 得到的 [ByteArray] 等价于该帧完整的 IDAT 数据，可直接用于 PNG 构建。
     */
    private fun mergeFdatChunks(fdatChunks: List<ByteArray>): ByteArray {
        val totalSize = fdatChunks.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (chunk in fdatChunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }
        return result
    }

    /**
     * 构建帧 PNG：从合并后的 fdAT 数据创建完整的 PNG 字节流。
     *
     * 注意：[idatData] 应为同一帧所有 fdAT chunk 合并后的数据，
     * 且每个分片的 4 字节序号前缀已在累积时剥离（见 [mergeFdatChunks]）。
     */
    private fun buildFrame(
        fctl: Chunk,
        idatData: ByteArray,
        width: Int,
        height: Int,
        ihdrRaw: ByteArray,
        ancillaryChunks: List<ByteArray>,
    ): ApngFrame {
        val fc = parseFctl(fctl.data)

        // 如果帧尺寸与原图不同，需要修改 IHDR 中的宽高
        val frameIhdr = if (fc.width != width || fc.height != height) {
            modifyIhdr(ihdrRaw, fc.width, fc.height)
        } else {
            ihdrRaw
        }

        // 构建 PNG：签名 + IHDR + 辅助 chunk + IDAT + IEND
        val pngBytes = buildPng(
            ihdrRaw = frameIhdr,
            ancillaryChunks = ancillaryChunks,
            idatData = idatData,
        )

        // 计算延迟（毫秒）
        val delayDen = if (fc.delayDen == 0) 100 else fc.delayDen
        val delayMs = (fc.delayNum.toLong() * 1000) / delayDen

        return ApngFrame(
            width = fc.width,
            height = fc.height,
            xOffset = fc.xOffset,
            yOffset = fc.yOffset,
            delayMs = delayMs,
            disposeOp = fc.disposeOp,
            blendOp = fc.blendOp,
            pngBytes = pngBytes,
        )
    }

    /**
     * 从 IDAT chunks 构建帧（当 fcTL 出现在 IDAT 之前时，第一帧使用 IDAT 数据）。
     *
     * 与 [buildFrame] 的区别：IDAT 数据没有 sequence_number 前缀，无需跳过 4 字节。
     * 支持 PNG 规范允许多个 IDAT chunk 分片的情况。
     */
    private fun buildFrameFromIdat(
        fctl: Chunk,
        idatChunks: List<Chunk>,
        width: Int,
        height: Int,
        ihdrRaw: ByteArray,
        ancillaryChunks: List<ByteArray>,
    ): ApngFrame {
        val fc = parseFctl(fctl.data)

        // 合并所有 IDAT 数据（IDAT 没有 sequence_number 前缀，与 fdAT 不同）
        val idatData = ByteArray(idatChunks.sumOf { it.data.size })
        var offset = 0
        for (chunk in idatChunks) {
            System.arraycopy(chunk.data, 0, idatData, offset, chunk.data.size)
            offset += chunk.data.size
        }

        // 如果帧尺寸与原图不同，需要修改 IHDR 中的宽高
        val frameIhdr = if (fc.width != width || fc.height != height) {
            modifyIhdr(ihdrRaw, fc.width, fc.height)
        } else {
            ihdrRaw
        }

        // 构建 PNG：签名 + IHDR + 辅助 chunk + IDAT + IEND
        val pngBytes = buildPng(
            ihdrRaw = frameIhdr,
            ancillaryChunks = ancillaryChunks,
            idatData = idatData,
        )

        // 计算延迟（毫秒）
        val delayDen = if (fc.delayDen == 0) 100 else fc.delayDen
        val delayMs = (fc.delayNum.toLong() * 1000) / delayDen

        return ApngFrame(
            width = fc.width,
            height = fc.height,
            xOffset = fc.xOffset,
            yOffset = fc.yOffset,
            delayMs = delayMs,
            disposeOp = fc.disposeOp,
            blendOp = fc.blendOp,
            pngBytes = pngBytes,
        )
    }

    /**
     * 构建默认帧（从 IDAT chunk 创建）。
     */
    private fun buildDefaultFrame(
        idatChunks: List<Chunk>,
        width: Int,
        height: Int,
        ihdrRaw: ByteArray,
        ancillaryChunks: List<ByteArray>,
    ): ApngFrame {
        // 合并所有 IDAT 数据
        val idatData = ByteArray(idatChunks.sumOf { it.data.size })
        var offset = 0
        for (chunk in idatChunks) {
            System.arraycopy(chunk.data, 0, idatData, offset, chunk.data.size)
            offset += chunk.data.size
        }

        val pngBytes = buildPng(
            ihdrRaw = ihdrRaw,
            ancillaryChunks = ancillaryChunks,
            idatData = idatData,
        )

        return ApngFrame(
            width = width,
            height = height,
            xOffset = 0,
            yOffset = 0,
            delayMs = 100, // 默认 100ms
            disposeOp = DisposeOp.NONE,
            blendOp = BlendOp.SOURCE,
            pngBytes = pngBytes,
        )
    }

    /**
     * 构建完整 PNG 字节流。
     */
    private fun buildPng(
        ihdrRaw: ByteArray,
        ancillaryChunks: List<ByteArray>,
        idatData: ByteArray,
    ): ByteArray {
        // PNG = 签名(8) + IHDR + 辅助chunk + IDAT + IEND
        val iendRaw = buildIendChunk()
        val idatRaw = buildIdatChunk(idatData)

        val totalSize = PNG_SIGNATURE.size +
            ihdrRaw.size +
            ancillaryChunks.sumOf { it.size } +
            idatRaw.size +
            iendRaw.size

        val result = ByteArray(totalSize)
        var pos = 0

        // 签名
        System.arraycopy(PNG_SIGNATURE, 0, result, pos, PNG_SIGNATURE.size)
        pos += PNG_SIGNATURE.size

        // IHDR
        System.arraycopy(ihdrRaw, 0, result, pos, ihdrRaw.size)
        pos += ihdrRaw.size

        // 辅助 chunk
        for (chunk in ancillaryChunks) {
            System.arraycopy(chunk, 0, result, pos, chunk.size)
            pos += chunk.size
        }

        // IDAT
        System.arraycopy(idatRaw, 0, result, pos, idatRaw.size)
        pos += idatRaw.size

        // IEND
        System.arraycopy(iendRaw, 0, result, pos, iendRaw.size)

        return result
    }

    /**
     * 构建原始 chunk 字节流（length + type + data + CRC）。
     */
    private fun buildRawChunk(chunk: Chunk): ByteArray {
        val lengthBytes = writeInt32(chunk.data.size)
        val typeBytes = chunk.type.toByteArray(Charsets.US_ASCII)
        val crc = Crc32.compute(typeBytes, 0, typeBytes.size, chunk.data, 0, chunk.data.size)
        val crcBytes = writeInt32(crc)

        return lengthBytes + typeBytes + chunk.data + crcBytes
    }

    /**
     * 构建 IDAT chunk。
     */
    private fun buildIdatChunk(data: ByteArray): ByteArray {
        val lengthBytes = writeInt32(data.size)
        val typeBytes = "IDAT".toByteArray(Charsets.US_ASCII)
        val crc = Crc32.compute(typeBytes, 0, typeBytes.size, data, 0, data.size)
        val crcBytes = writeInt32(crc)

        return lengthBytes + typeBytes + data + crcBytes
    }

    /**
     * 构建 IEND chunk。
     */
    private fun buildIendChunk(): ByteArray {
        val lengthBytes = writeInt32(0)
        val typeBytes = "IEND".toByteArray(Charsets.US_ASCII)
        val crc = Crc32.compute(typeBytes, 0, typeBytes.size)
        val crcBytes = writeInt32(crc)

        return lengthBytes + typeBytes + crcBytes
    }

    /**
     * 修改 IHDR 中的宽高。
     */
    private fun modifyIhdr(ihdrRaw: ByteArray, width: Int, height: Int): ByteArray {
        val result = ihdrRaw.copyOf()
        // IHDR 结构: length(4) + type(4) + width(4) + height(4) + ...
        // width 在 offset 8, height 在 offset 12
        val widthBytes = writeInt32(width)
        val heightBytes = writeInt32(height)
        System.arraycopy(widthBytes, 0, result, 8, 4)
        System.arraycopy(heightBytes, 0, result, 12, 4)

        // 重新计算 CRC
        val typeBytes = "IHDR".toByteArray(Charsets.US_ASCII)
        val dataStart = 8 // 跳过 length + type
        val dataEnd = result.size - 4 // 跳过 CRC
        val crc = Crc32.compute(typeBytes, 0, typeBytes.size, result, dataStart, dataEnd - dataStart)
        val crcBytes = writeInt32(crc)
        System.arraycopy(crcBytes, 0, result, dataEnd, 4)

        return result
    }

    // ============================================================
    // 工具函数
    // ============================================================

    private fun readInt32(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun readInt16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)
    }

    private fun writeInt32(value: Int): ByteArray {
        return byteArrayOf(
            ((value ushr 24) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
    }
}

/**
 * CRC32 计算（纯 Kotlin 实现，用于 PNG chunk 校验）。
 */
private object Crc32 {
    private val table = IntArray(256)

    init {
        for (n in 0..255) {
            var c = n
            for (k in 0..7) {
                c = if (c and 1 != 0) (-0x12477CE0) xor (c ushr 1) else c ushr 1
            }
            table[n] = c
        }
    }

    fun compute(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (i in offset until offset + length) {
            crc = table[(crc xor data[i].toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc xor 0xFFFFFFFF.toInt()
    }

    fun compute(data1: ByteArray, offset1: Int, length1: Int, data2: ByteArray, offset2: Int, length2: Int): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (i in offset1 until offset1 + length1) {
            crc = table[(crc xor data1[i].toInt()) and 0xFF] xor (crc ushr 8)
        }
        for (i in offset2 until offset2 + length2) {
            crc = table[(crc xor data2[i].toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc xor 0xFFFFFFFF.toInt()
    }
}
