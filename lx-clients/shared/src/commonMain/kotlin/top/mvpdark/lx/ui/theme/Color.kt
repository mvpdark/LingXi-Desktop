package top.mvpdark.lx.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor

/**
 * 灵犀品牌色彩系统 — Noir Aurum（黑曜鎏金）。
 *
 * 设计哲学：黑色作为存在的缺席，金色作为意义的出现。
 * 二元色彩系统：黑:金 = 10:1，东方漆器工艺 × 苹果极简主义。
 */

// ---- 核心二元色 ----

/** 曜石黑基底。 */
val Obsidian = Color(0xFF0A0A0C)

/** 曜石黑中心微提亮（径向渐变用）。 */
val ObsidianLift = Color(0xFF121214)

/** 曜石黑卡片表面。 */
val ObsidianSurface = Color(0xFF1A1A1D)

/** 曜石黑次级表面。 */
val ObsidianSurfaceVariant = Color(0xFF222226)

/** 香槟金主强调色。 */
val Champagne = Color(0xFFD4AF37)

/** 高光金（灵犀点/焦点）。 */
val ChampagneBright = Color(0xFFE8C97A)

/** 暗金（文字/图标）。 */
val ChampagneDeep = Color(0xFF9A7B2E)

/** 古铜金（分割线/次要元素）。 */
val ChampagneAntique = Color(0xFF8B6914)

/** 阴影金。 */
val ChampagneShadow = Color(0xFF5A4619)

// ---- 浅色模式（珍珠白变体） ----

val PearlBackground = Color(0xFFF5F3EE)
val PearlSurface = Color(0xFFFFFFFF)
val PearlSurfaceVariant = Color(0xFFEDEAE3)
val CharcoalLight = Color(0xFF2C2A26)
val CharcoalLightVariant = Color(0xFF5A5550)
val PearlOutline = Color(0xFFC9C2B6)

// ---- AI/用户气泡色 ----

/** AI 气泡：曜石黑表面 + 金线描边。 */
val NoirAiBubble = Color(0xFF1A1A1D)
val NoirAiBubbleLight = Color(0xFFFFFFFF)

/** 用户气泡：金色渐变。 */
val NoirUserBubbleStart = Color(0xFFD4AF37)
val NoirUserBubbleEnd = Color(0xFF9A7B2E)

/** 浅色模式用户气泡：淡金渐变。 */
val NoirUserBubbleStartLight = Color(0xFFE8C97A)
val NoirUserBubbleEndLight = Color(0xFFD4AF37)

/** 金色分割线。 */
val GoldDivider = Color(0xFF3A3528)

// ---- 浅色模式扩展 ----

val LightOnSurface = CharcoalLight
val LightOnSurfaceVariant = CharcoalLightVariant
val LightSurface = PearlSurface
val LightSurfaceVariant = PearlSurfaceVariant
val LightOutline = PearlOutline
val LightError = Color(0xFFB3261E)

// ---- 深色模式扩展 ----

val DarkBackground = Obsidian
val DarkSurface = ObsidianSurface
val DarkSurfaceVariant = ObsidianSurfaceVariant
val DarkOnSurface = ChampagneBright
val DarkOnSurfaceVariant = ChampagneDeep
val DarkOutline = ChampagneAntique
val DarkAiBubble = NoirAiBubble
val DarkUserBubbleStart = NoirUserBubbleStart
val DarkUserBubbleEnd = NoirUserBubbleEnd
val DarkError = Color(0xFFE8C97A)

/**
 * 浅色 Material3 配色方案（珍珠白 + 香槟金）。
 */
val LxLightColorScheme = lightColorScheme(
    primary = Champagne,
    onPrimary = Obsidian,
    primaryContainer = Color(0xFFE8C97A),
    onPrimaryContainer = CharcoalLight,
    secondary = ChampagneDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEAE3),
    onSecondaryContainer = CharcoalLight,
    tertiary = ChampagneAntique,
    onTertiary = Color.White,
    background = PearlBackground,
    onBackground = CharcoalLight,
    surface = PearlSurface,
    onSurface = CharcoalLight,
    surfaceVariant = PearlSurfaceVariant,
    onSurfaceVariant = CharcoalLightVariant,
    outline = PearlOutline,
    outlineVariant = Color(0xFFD4CFC4),
    error = LightError,
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

/**
 * 深色 Material3 配色方案（曜石黑 + 香槟金）。
 */
val LxDarkColorScheme = darkColorScheme(
    primary = Champagne,
    onPrimary = Obsidian,
    primaryContainer = ChampagneShadow,
    onPrimaryContainer = ChampagneBright,
    secondary = ChampagneBright,
    onSecondary = Obsidian,
    secondaryContainer = ObsidianSurfaceVariant,
    onSecondaryContainer = ChampagneBright,
    tertiary = ChampagneAntique,
    onTertiary = Obsidian,
    background = Obsidian,
    onBackground = ChampagneBright,
    surface = ObsidianSurface,
    onSurface = ChampagneBright,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = ChampagneDeep,
    outline = ChampagneAntique,
    outlineVariant = GoldDivider,
    error = DarkError,
    onError = Obsidian,
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/**
 * 用户气泡金色渐变画刷。
 * @param isDark 是否深色模式。
 */
fun userBubbleBrush(isDark: Boolean): Brush = if (isDark) {
    Brush.linearGradient(
        colors = listOf(NoirUserBubbleStart, NoirUserBubbleEnd),
    )
} else {
    Brush.linearGradient(
        colors = listOf(NoirUserBubbleStartLight, NoirUserBubbleEndLight),
    )
}

// ============================================================
// 兼容别名层（向后兼容旧命名引用，全部指向 Noir Aurum 新色值）
//
// Noir Aurum 改造后，旧 Quiet Materiality 命名（SageGreen / CreamBackground 等）
// 统一映射为黑金二元色值，所有引用这些常量的 UI 组件无需修改即可自动切换主题。
// ============================================================

/** 旧名兼容：曜石黑背景（ObsidianBackground）。 */
val ObsidianBackground = Obsidian

/** 旧名兼容：曜石黑（多处引用 ObsidianBlack）。 */
val ObsidianBlack = Obsidian

/** 旧名兼容：奶油背景（已废弃，指向曜石黑）。 */
val CreamBackground = Obsidian

/** 旧名兼容：鼠尾草绿（已废弃，指向香槟金）。 */
val SageGreen = Champagne

/** 旧名兼容：香槟金旧名（ChampagneGold）。 */
val ChampagneGold = Champagne

/** 旧名兼容：高光金旧名（GoldBright）。 */
val GoldBright = ChampagneBright

/** 旧名兼容：暗金旧名（GoldDeep）。 */
val GoldDeep = ChampagneDeep

/** 旧名兼容：古铜金旧名（GoldAntique）。 */
val GoldAntique = ChampagneAntique

/** 旧名兼容：鼠尾草绿深色（已废弃，指向古铜金）。 */
val SageGreenDeep = ChampagneAntique

/** 旧名兼容：阴影金旧名（GoldShadow）。 */
val GoldShadow = ChampagneShadow

/** 旧名兼容：米色分割线（已废弃，指向金色分割线）。 */
val BeigeDivider = GoldDivider

/** 旧名兼容：金色上的文字（曜石黑）。 */
val OnGold = Obsidian

/** 旧名兼容：黑底主文字（高光金）。 */
val ObsidianOnSurface = ChampagneBright

/** 旧名兼容：黑底次级文字（暗金）。 */
val ObsidianOnSurfaceVariant = ChampagneDeep

/** 旧名兼容：黑底描边（古铜金）。 */
val ObsidianOutline = ChampagneAntique

/** 金色发丝线（极淡金，1px 边框/分割线用，alpha 约 12%）。 */
val GoldHairline = Color(0x1ED4AF37)

/** 旧名兼容：AI 气泡深黑。 */
val AiBubble = NoirAiBubble

/** 旧名兼容：AI 气泡深黑别名。 */
val AiBubbleDark = NoirAiBubble

/** 旧名兼容：AI 气泡金色边框。 */
val AiBubbleBorder = Champagne

/** 旧名兼容：用户气泡金色渐变起点。 */
val UserBubbleStart = NoirUserBubbleStart

/** 旧名兼容：用户气泡金色渐变终点。 */
val UserBubbleEnd = NoirUserBubbleEnd

/** 旧名兼容：用户气泡金色渐变起点（语义化别名）。 */
val UserBubbleGoldStart = NoirUserBubbleStart

/** 旧名兼容：用户气泡金色渐变终点（语义化别名）。 */
val UserBubbleGoldEnd = NoirUserBubbleEnd

/** 旧名兼容：浅色模式较暗金（已废弃，指向暗金）。 */
val LightGold = ChampagneDeep

/** 旧名兼容：浅色模式背景（指向珍珠白底）。 */
val LightBackground = PearlBackground

/**
 * 旧名兼容：Noir Aurum 专属配色方案（已与深色方案统一）。
 */
val NoirAurumColorScheme = LxDarkColorScheme

/**
 * 旧名兼容：AI 气泡金色边框颜色。
 *
 * @param isDark 是否深色模式（深色返回香槟金，浅色返回古铜金）。
 */
fun aiBubbleBorder(isDark: Boolean): Color = if (isDark) {
    Champagne
} else {
    ChampagneAntique
}

/**
 * 旧名兼容：Noir Aurum 用户气泡金色渐变画刷。
 *
 * 金色莳绘渐变：暗金（ChampagneDeep）→ 香槟金（Champagne）。
 */
fun noirAurumUserBubbleBrush(): Brush = Brush.linearGradient(
    colors = listOf(ChampagneDeep, Champagne),
)

/**
 * 旧名兼容：Noir Aurum AI 气泡背景画刷。
 *
 * 漆器深黑表面：ObsidianSurface 纯色，配合 1px 金色发丝线边框使用。
 */
fun noirAurumAiBubbleBrush(): Brush = SolidColor(ObsidianSurface)
