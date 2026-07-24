package top.mvpdark.lx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.viewmodel.koinViewModel
import top.mvpdark.lx.ui.auth.AuthViewModel
import top.mvpdark.lx.ui.theme.ChampagneGold
import top.mvpdark.lx.ui.theme.GoldBright
import top.mvpdark.lx.ui.theme.GoldDeep
import top.mvpdark.lx.ui.theme.LxThemeStyle
import top.mvpdark.lx.ui.theme.LocalThemeStyle
import top.mvpdark.lx.ui.theme.ObsidianBlack
import top.mvpdark.lx.ui.theme.ObsidianSurface
import top.mvpdark.lx.ui.theme.ObsidianSurfaceVariant

/**
 * 登录页面（Noir Aurum 黑曜鎏金风格）。
 *
 * 全屏居中卡片布局：曜石黑底 + 极淡金色径向渐变；登录卡片为 ObsidianSurface
 * 配 1dp 香槟金描边（alpha 0.3）、24dp 大圆角；标题"灵犀"高光金 Light 字重宽字距；
 * 输入框 ObsidianSurfaceVariant 底 + 香槟金描边（聚焦 alpha 0.5）；登录按钮金色渐变
 * （ChampagneGold → GoldDeep）配曜石黑文字、28dp 胶囊形。
 *
 * 保留团团（猫娘 AI 助手）欢迎语文案不变，仅切换色彩语言。
 *
 * @param authViewModel 认证 ViewModel（Koin 注入）。
 * @param onNavigateHome 登录成功后导航到首页。
 */
@Composable
fun LoginScreen(
    authViewModel: AuthViewModel = koinViewModel(),
    onNavigateHome: () -> Unit = {},
) {
    val state by authViewModel.uiState.collectAsState()
    val isNoirAurum = LocalThemeStyle.current == LxThemeStyle.NOIR_AURUM

    // 登录成功后导航到首页
    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) {
            onNavigateHome()
        }
    }

    var passwordVisible by remember { mutableStateOf(false) }

    // Noir Aurum：曜石黑底 + 极淡金色径向渐变（中心微亮，模拟概念画布）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (isNoirAurum) {
                    Modifier.background(
                        Brush.radialGradient(
                            colors = listOf(ObsidianSurface, ObsidianBlack),
                        ),
                    )
                } else {
                    Modifier.background(MaterialTheme.colorScheme.background)
                }
            )
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        // 登录卡片：ObsidianSurface 底 + 1dp 香槟金描边（alpha 0.3）+ 24dp 大圆角
        Card(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(24.dp)
                .then(
                    if (isNoirAurum) {
                        Modifier.border(
                            width = 1.dp,
                            color = ChampagneGold.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(24.dp),
                        )
                    } else {
                        Modifier
                    }
                ),
            colors = CardDefaults.cardColors(
                containerColor = if (isNoirAurum) {
                    ObsidianSurface
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(
                defaultElevation = if (isNoirAurum) 0.dp else 2.dp,
            ),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 28.dp, vertical = 32.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 品牌标识区 —— 标题"灵犀"：高光金 GoldBright，Light 字重，宽字距
                Text(
                    text = "灵犀",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = if (isNoirAurum) FontWeight.Light else FontWeight.Bold,
                    color = if (isNoirAurum) GoldBright else MaterialTheme.colorScheme.primary,
                    letterSpacing = if (isNoirAurum) 4.sp else 0.sp,
                )
                // 副标题：暗金 GoldDeep
                Text(
                    text = "摄影师的 AI 助手",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isNoirAurum) GoldDeep else MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = if (isNoirAurum) 2.sp else 0.sp,
                )

                Spacer(Modifier.height(20.dp))

                // 团团（猫娘助手）欢迎语 —— 文案不变，Noir Aurum 用暗金辅助色
                Text(
                    text = "喵～团团在这里等你喵！\n欢迎回来，今天想和团团聊点什么呢？(=^･ω･^=)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isNoirAurum) {
                        GoldDeep.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(24.dp))

                // Noir Aurum 输入框配色：ObsidianSurfaceVariant 底 + 香槟金描边 + 12dp 圆角
                val noirTextFieldColors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ObsidianSurfaceVariant,
                    unfocusedContainerColor = ObsidianSurfaceVariant,
                    focusedTextColor = GoldBright,
                    unfocusedTextColor = GoldBright,
                    focusedBorderColor = ChampagneGold.copy(alpha = 0.5f),
                    unfocusedBorderColor = ChampagneGold.copy(alpha = 0.2f),
                    cursorColor = ChampagneGold,
                    focusedLabelColor = GoldBright,
                    unfocusedLabelColor = GoldDeep,
                    focusedLeadingIconColor = GoldBright,
                    unfocusedLeadingIconColor = GoldDeep,
                )
                val noirTextFieldShape = RoundedCornerShape(12.dp)

                // 用户名输入
                OutlinedTextField(
                    value = state.username,
                    onValueChange = authViewModel::onUsernameChange,
                    label = { Text("用户名") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = "用户名")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    isError = state.error != null,
                    shape = if (isNoirAurum) noirTextFieldShape else MaterialTheme.shapes.medium,
                    colors = if (isNoirAurum) noirTextFieldColors else OutlinedTextFieldDefaults.colors(),
                )

                // 密码输入
                OutlinedTextField(
                    value = state.password,
                    onValueChange = authViewModel::onPasswordChange,
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Lock, contentDescription = "密码")
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    isError = state.error != null,
                    shape = if (isNoirAurum) noirTextFieldShape else MaterialTheme.shapes.medium,
                    colors = if (isNoirAurum) {
                        OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = ObsidianSurfaceVariant,
                            unfocusedContainerColor = ObsidianSurfaceVariant,
                            focusedTextColor = GoldBright,
                            unfocusedTextColor = GoldBright,
                            focusedBorderColor = ChampagneGold.copy(alpha = 0.5f),
                            unfocusedBorderColor = ChampagneGold.copy(alpha = 0.2f),
                            cursorColor = ChampagneGold,
                            focusedLabelColor = GoldBright,
                            unfocusedLabelColor = GoldDeep,
                            focusedLeadingIconColor = GoldBright,
                            unfocusedLeadingIconColor = GoldDeep,
                            focusedTrailingIconColor = GoldBright,
                            unfocusedTrailingIconColor = GoldDeep,
                        )
                    } else {
                        OutlinedTextFieldDefaults.colors()
                    },
                )

                // 错误提示：Noir Aurum 用金色（GoldBright #E8C97A）而非红色
                if (state.error != null) {
                    Text(
                        text = state.error!!,
                        color = if (isNoirAurum) GoldBright else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                }

                Spacer(Modifier.height(16.dp))

                // 登录 / 注册按钮 —— Noir Aurum：金色渐变（ChampagneGold → GoldDeep）
                // + 曜石黑文字 + 28dp 胶囊形 + Medium 字重 + 2.sp 宽字距
                Button(
                    onClick = {
                        if (state.isRegisterMode) {
                            authViewModel.register()
                        } else {
                            authViewModel.login()
                        }
                    },
                    enabled = !state.isLoading,
                    shape = RoundedCornerShape(if (isNoirAurum) 28.dp else 12.dp),
                    colors = if (isNoirAurum) {
                        ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 0.dp,
                        disabledElevation = 0.dp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .then(
                            if (isNoirAurum) {
                                Modifier.background(
                                    brush = if (state.isLoading) {
                                        Brush.linearGradient(
                                            listOf(
                                                GoldDeep.copy(alpha = 0.6f),
                                                ChampagneGold.copy(alpha = 0.4f),
                                            ),
                                        )
                                    } else {
                                        Brush.linearGradient(listOf(ChampagneGold, GoldDeep))
                                    },
                                    shape = RoundedCornerShape(28.dp),
                                )
                            } else {
                                Modifier
                            }
                        ),
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = if (isNoirAurum) ObsidianBlack else MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            text = if (state.isRegisterMode) "注册" else "登录",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isNoirAurum) ObsidianBlack else Color.Unspecified,
                            fontWeight = if (isNoirAurum) FontWeight.Medium else FontWeight.SemiBold,
                            letterSpacing = if (isNoirAurum) 2.sp else 0.sp,
                        )
                    }
                }

                // 切换登录/注册模式 —— Noir Aurum 辅助文字用暗金 alpha 0.7
                TextButton(onClick = authViewModel::toggleRegisterMode) {
                    Text(
                        text = if (state.isRegisterMode) {
                            "已有账号？返回登录"
                        } else {
                            "还没有账号？立即注册"
                        },
                        color = if (isNoirAurum) GoldDeep.copy(alpha = 0.7f) else Color.Unspecified,
                    )
                }
            }
        }
    }
}
