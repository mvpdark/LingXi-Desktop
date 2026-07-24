package top.mvpdark.lx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.mvpdark.lx.ui.theme.ChampagneGold
import top.mvpdark.lx.ui.theme.GoldBright
import top.mvpdark.lx.ui.theme.LxThemeStyle
import top.mvpdark.lx.ui.theme.LocalThemeStyle
import top.mvpdark.lx.ui.theme.ObsidianSurface

/**
 * 加载指示器组件（Noir Aurum 黑曜鎏金风格）。
 *
 * 居中显示一个 Material3 圆形进度指示器，可选附带提示文案。
 * Noir Aurum 模式下进度指示器使用香槟金（ChampagneGold #D4AF37），
 * 附带文案时背景融入 ObsidianSurface 漆器深黑表面。
 *
 * @param modifier 修饰符。
 * @param message 提示文案，为空则只显示指示器。
 * @param size 指示器尺寸（dp）。
 */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    message: String? = null,
    size: Int = 40,
) {
    val isNoirAurum = LocalThemeStyle.current == LxThemeStyle.NOIR_AURUM
    // Noir Aurum：进度条/转圈色用香槟金 ChampagneGold（#D4AF37）
    val indicatorColor = if (isNoirAurum) ChampagneGold else MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (message != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .then(
                        if (isNoirAurum) {
                            // 背景融入 Obsidian 漆器深黑表面 + 20dp 圆角
                            Modifier
                                .background(ObsidianSurface, RoundedCornerShape(20.dp))
                                .padding(32.dp)
                        } else {
                            Modifier.padding(16.dp)
                        }
                    ),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(size.dp),
                    strokeWidth = 3.dp,
                    color = indicatorColor,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isNoirAurum) GoldBright else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(size.dp),
                strokeWidth = 3.dp,
                color = indicatorColor,
            )
        }
    }
}
