package top.mvpdark.lx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.mvpdark.lx.ui.theme.GoldBright
import top.mvpdark.lx.ui.theme.GoldDeep
import top.mvpdark.lx.ui.theme.LxThemeStyle
import top.mvpdark.lx.ui.theme.LocalThemeStyle

/**
 * 占位页面（Noir Aurum 黑曜鎏金风格），用于尚未实现的功能模块。
 *
 * Noir Aurum 模式下文字使用高光金（GoldBright），副标题使用暗金（GoldDeep）。
 *
 * @param title 标题。
 * @param subtitle 副标题/说明。
 */
@Composable
fun PlaceholderScreen(
    title: String,
    subtitle: String = "",
) {
    val isNoirAurum = LocalThemeStyle.current == LxThemeStyle.NOIR_AURUM

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = if (isNoirAurum) FontWeight.Light else FontWeight.SemiBold,
            // Noir Aurum：文字色用高光金 GoldBright（即 ChampagneBright #E8C97A）
            color = if (isNoirAurum) GoldBright else MaterialTheme.colorScheme.primary,
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                // Noir Aurum：副标题用暗金 GoldDeep（即 ChampagneDeep #9A7B2E）
                color = if (isNoirAurum) GoldDeep else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
