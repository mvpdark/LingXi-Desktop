package top.mvpdark.lx.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 灵犀主题 — Noir Aurum（黑曜鎏金）。
 *
 * 默认强制深色模式，展现黑金奢华质感。
 *
 * @param darkTheme 是否使用深色模式，默认 true（黑金主题强制深色）。
 * @param content 主题包裹的内容。
 */
@Composable
fun LxTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) {
        LxDarkColorScheme
    } else {
        LxLightColorScheme
    }

    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LxTypography,
            content = content,
        )
    }
}

/** 当前是否深色模式的全局可读值，供气泡等组件取色用。 */
val LocalDarkTheme = staticCompositionLocalOf { true }

// ============================================================
// 兼容垫片（向后兼容旧主题风格枚举引用）
//
// Noir Aurum 改造后渲染始终为黑曜鎏金深色模式；保留枚举与 LocalThemeStyle
// 仅为兼容既有组件中的 isNoirAurum 分支引用，使其始终命中 NOIR_AURUM 分支。
// ============================================================

/**
 * 灵犀主题风格枚举（兼容旧引用）。
 *
 * - [LxThemeStyle.NOIR_AURUM]：黑曜鎏金（当前唯一生效风格）。
 * - [LxThemeStyle.QUIET_MATERIALITY]：沉静物性（已废弃，保留以兼容旧分支）。
 */
enum class LxThemeStyle {
    QUIET_MATERIALITY,
    NOIR_AURUM,
}

/** 当前主题风格的全局可读值（兼容旧引用），默认 [LxThemeStyle.NOIR_AURUM]。 */
val LocalThemeStyle = staticCompositionLocalOf { LxThemeStyle.NOIR_AURUM }
