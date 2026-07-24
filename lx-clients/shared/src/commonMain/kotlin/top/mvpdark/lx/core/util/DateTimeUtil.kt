package top.mvpdark.lx.core.util

/**
 * ISO 8601 时间戳格式化工具。
 *
 * 将后端返回的 ISO 8601 时间字符串（如 "2026-07-18T13:52:14.955045+00:00"）
 * 按字符串自带的时区偏移还原为 UTC 时刻，再换算为本机时区，
 * 输出用户友好的显示格式。
 */

/**
 * 格式化会话时间为友好显示。
 *
 * 支持以下输入格式：
 * - "2026-07-18T13:52:14.955045+00:00"（带微秒和时区）
 * - "2026-07-18T13:52:14+00:00" / "2026-07-18T09:52:14-05:00"（带正/负时区偏移）
 * - "2026-07-18T13:52:14Z"（UTC 标记）
 * - "2026-07-18T13:52:14"（无时区，按 UTC 处理）
 * - "2026-07-18 13:52:14"（空格分隔）
 *
 * 输出格式：
 * - 今年： "7月18日 13:52"
 * - 非今年： "2025-12-31 13:52"
 * - 解析失败： 返回原始字符串
 *
 * @param isoTimestamp ISO 8601 格式的时间字符串。
 * @return 用户友好的时间显示。
 */
fun formatSessionTime(isoTimestamp: String): String {
    if (isoTimestamp.isBlank()) return ""

    return try {
        val parsed = parseIso8601(isoTimestamp) ?: return isoTimestamp
        val now = parseIso8601(getCurrentIso8601()) ?: return isoTimestamp

        val isThisYear = parsed.year == now.year
        val time = "${parsed.hour.toString().padStart(2, '0')}:${parsed.minute.toString().padStart(2, '0')}"

        if (isThisYear) {
            "${parsed.month}月${parsed.day}日 $time"
        } else {
            "${parsed.year}-${parsed.month.toString().padStart(2, '0')}-${parsed.day.toString().padStart(2, '0')} $time"
        }
    } catch (_: Exception) {
        isoTimestamp
    }
}

/**
 * 格式化消息时间戳（仅显示时分）。
 *
 * @param isoTimestamp ISO 8601 格式的时间字符串。
 * @return "HH:MM" 格式或原始字符串。
 */
fun formatMessageTime(isoTimestamp: String): String {
    if (isoTimestamp.isBlank()) return ""

    return try {
        val parsed = parseIso8601(isoTimestamp) ?: return isoTimestamp
        "${parsed.hour.toString().padStart(2, '0')}:${parsed.minute.toString().padStart(2, '0')}"
    } catch (_: Exception) {
        isoTimestamp
    }
}

/** 解析后的日期时间组件。 */
private data class DateTimeComponents(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

/**
 * 解析 ISO 8601 字符串，并按字符串自带时区偏移换算为本机时区的日期时间组件。
 *
 * - 支持 "+HH:MM" / "-HH:MM" / "+HHMM" / "+HH" / "Z" 时区形式（正确处理负偏移）；
 * - 无显式时区时按 UTC 处理（服务端存储用 UTC）；
 * - 最终通过 [localUtcOffsetMinutes] 把 UTC 时刻换算为本地显示时刻。
 *
 * 不依赖 java.time（保证 commonMain 可用，且兼容 minSdk 24 无 desugaring 的 Android）。
 */
private fun parseIso8601(input: String): DateTimeComponents? {
    if (input.isBlank()) return null

    return try {
        // 提取日期和时间部分：取 "T" 或空格分隔的两段
        val normalized = input.trim().replace(' ', 'T')
        val parts = normalized.split("T")
        if (parts.size < 2) return null

        val dateParts = parts[0].split("-")
        if (dateParts.size < 3) return null

        val year = dateParts[0].toIntOrNull() ?: return null
        val month = dateParts[1].toIntOrNull() ?: return null
        val day = dateParts[2].toIntOrNull() ?: return null

        // 分离时间与时区：时间只含数字、冒号、小数点，
        // 'Z'/'+'/'-' 均标志时区起点（'-' 只会出现在负偏移中，时间本身不含 '-'）
        val timeAndZone = parts[1]
        var zoneStart = -1
        var zoneSign = 1
        for (i in timeAndZone.indices) {
            when (timeAndZone[i]) {
                'Z', 'z' -> { zoneStart = i; zoneSign = 0; break }
                '+' -> { zoneStart = i; zoneSign = 1; break }
                '-' -> { zoneStart = i; zoneSign = -1; break }
            }
        }
        val timePart = if (zoneStart >= 0) timeAndZone.substring(0, zoneStart) else timeAndZone

        // 时区偏移分钟数：Z 或无显式时区 → 0；否则解析 ±HH:MM / ±HHMM / ±HH
        var offsetMinutes = 0
        if (zoneStart >= 0 && zoneSign != 0) {
            val zone = timeAndZone.substring(zoneStart + 1).replace(":", "")
            if (zone.isEmpty() || zone.length > 4) return null
            val offsetHour = zone.substring(0, minOf(2, zone.length)).toIntOrNull() ?: return null
            val offsetMinute = if (zone.length > 2) {
                zone.substring(2).toIntOrNull() ?: return null
            } else {
                0
            }
            offsetMinutes = zoneSign * (offsetHour * 60 + offsetMinute)
        }

        // 去掉微秒部分
        val timeNoMicros = timePart.substringBefore(".")
        val timeParts = timeNoMicros.split(":")
        val hour = timeParts.getOrNull(0)?.toIntOrNull() ?: 0
        val minute = timeParts.getOrNull(1)?.toIntOrNull() ?: 0
        val second = timeParts.getOrNull(2)?.toIntOrNull() ?: 0

        // 先按声明偏移把书写时刻还原为 UTC 毫秒，再叠加本机时区偏移得到本地显示时刻
        val utcMillis = daysFromCivil(year, month, day) * 86_400_000L +
            (hour * 3600L + minute * 60L + second) * 1000L -
            offsetMinutes * 60_000L
        epochMillisToComponents(utcMillis + localUtcOffsetMinutes() * 60_000L)
    } catch (_: Exception) {
        null
    }
}

/**
 * 年月日转 epoch 天数（Howard Hinnant 的 days_from_civil 算法，支持 1970 年前后）。
 */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = if (y >= 0) y / 400 else (y - 399) / 400
    val yoe = y - era * 400 // [0, 399]
    val mp = if (month > 2) month - 3 else month + 9 // [0, 11]
    val doy = (153 * mp + 2) / 5 + day - 1 // [0, 365]
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy // [0, 146096]
    return era * 146_097L + doe - 719_468L
}

/** 向下取整除法（Kotlin 的 "/" 向零取整，负毫秒需要 floor 语义）。 */
private fun floorDiv(a: Long, b: Long): Long {
    val r = a / b
    return if ((a % b != 0L) && ((a < 0) != (b < 0))) r - 1 else r
}

/** epoch 毫秒转日期时间组件（对传入毫秒值直接展开，时区调整由调用方完成）。 */
private fun epochMillisToComponents(millis: Long): DateTimeComponents {
    val epochDay = floorDiv(millis, 86_400_000L)
    val millisOfDay = millis - epochDay * 86_400_000L
    val hour = (millisOfDay / 3_600_000L).toInt()
    val minute = ((millisOfDay % 3_600_000L) / 60_000L).toInt()
    val second = ((millisOfDay % 60_000L) / 1000L).toInt()
    val (year, month, day) = epochDayToYmd(epochDay)
    return DateTimeComponents(year, month, day, hour, minute, second)
}

/**
 * 获取当前时间的 ISO 8601 字符串（用于年份比较）。
 * 使用各平台已实现的 currentTimeMillis。
 */
private fun getCurrentIso8601(): String {
    val millis = currentTimeMillis()
    return millisToIso8601(millis)
}

/**
 * 毫秒时间戳转 ISO 8601 字符串（简化版，用于当前时间）。
 * 不依赖 java.time，手动计算。
 */
private fun millisToIso8601(millis: Long): String {
    val totalSeconds = millis / 1000
    val epochDay = totalSeconds / 86400
    val secondsInDay = totalSeconds % 86400

    val hour = (secondsInDay / 3600).toInt()
    val minute = ((secondsInDay % 3600) / 60).toInt()
    val second = (secondsInDay % 60).toInt()

    // 从 1970-01-01 开始计算日期
    val (year, month, day) = epochDayToYmd(epochDay)

    // 手动拼接，避免 String.format 在阿拉伯语等 locale 下输出非 ASCII 数字
    return buildString(20) {
        append(year.toString().padStart(4, '0'))
        append('-')
        append(month.toString().padStart(2, '0'))
        append('-')
        append(day.toString().padStart(2, '0'))
        append('T')
        append(hour.toString().padStart(2, '0'))
        append(':')
        append(minute.toString().padStart(2, '0'))
        append(':')
        append(second.toString().padStart(2, '0'))
    }
}

/**
 * 将 epoch 天数转换为年月日（支持 1970 年前后）。
 * 使用 Civil from Days 算法（Howard Hinnant）。
 */
private fun epochDayToYmd(epochDay: Long): Triple<Int, Int, Int> {
    val z = epochDay + 719468
    val era = if (z >= 0) z / 146097 else (z - 146096) / 146097
    val doe = (z - era * 146097).toInt() // [0, 146096]
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365 // [0, 399]
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100) // [0, 365]
    val mp = (5 * doy + 2) / 153 // [0, 11]
    val d = doy - (153 * mp + 2) / 5 + 1 // [1, 31]
    val m = if (mp < 10) mp + 3 else mp - 9 // [1, 12]
    val year = if (m <= 2) y + 1 else y
    return Triple(year.toInt(), m, d)
}
