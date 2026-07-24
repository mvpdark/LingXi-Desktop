package top.mvpdark.lx.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import top.mvpdark.lx.core.util.formatDouble
import top.mvpdark.lx.core.util.formatSessionTime
import top.mvpdark.lx.ui.auth.AuthViewModel
import top.mvpdark.lx.ui.chat.ChatViewModel
import top.mvpdark.lx.ui.theme.ChampagneGold
import top.mvpdark.lx.ui.theme.GoldBright
import top.mvpdark.lx.ui.theme.GoldDeep
import top.mvpdark.lx.ui.theme.LxThemeStyle
import top.mvpdark.lx.ui.theme.LocalThemeStyle
import top.mvpdark.lx.ui.theme.ObsidianSurface
import top.mvpdark.lx.ui.theme.ObsidianSurfaceVariant

/**
 * 首页（Noir Aurum 黑曜鎏金风格）。
 *
 * 设计哲学："Noir Aurum"（黑曜鎏金）——曜石黑为基底，香槟金为唯一光源语言。
 * - 曜石黑背景承载香槟金强调色，黑大于金十倍
 * - 2×2 功能卡片网格：ObsidianSurface 底 + 香槟金描边（alpha 0.2）+ 淡金图标底
 * - 最近会话列表：ObsidianSurfaceVariant 卡片 + 极细金边
 *
 * - 顶部导航栏：用户名（GoldBright Light）+ 余额（GoldDeep）+ 退出按钮（融入背景，无分隔线）
 * - 功能卡片网格：灵感(→chat) / 修图(→image-edit) / 更多
 * - 最近会话列表（前5条）
 *
 * @param authViewModel 认证 ViewModel（Koin 注入）。
 * @param chatViewModel 聊天 ViewModel（Koin 注入）。
 * @param onNavigateChat 导航到聊天页（sessionId 由新建/选择会话决定）。
 * @param onNavigateImageEdit 导航到改图页。
 * @param onLoggedOut 退出登录后的回调（回到登录页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    authViewModel: AuthViewModel = koinViewModel(),
    chatViewModel: ChatViewModel = koinViewModel(),
    onNavigateChat: (String) -> Unit = {},
    onNavigateImageEdit: () -> Unit = {},
    onLoggedOut: () -> Unit = {},
) {
    val authState by authViewModel.uiState.collectAsState()
    val chatState by chatViewModel.uiState.collectAsState()
    val isNoirAurum = LocalThemeStyle.current == LxThemeStyle.NOIR_AURUM

    // 进入首页刷新会话列表与用户信息
    LaunchedEffect(Unit) {
        chatViewModel.loadSessions()
        authViewModel.refreshUser()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        // Noir Aurum：标题用高光金 GoldBright，Light 字重
                        Text(
                            text = authState.user?.username ?: "灵犀",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (isNoirAurum) FontWeight.Light else FontWeight.SemiBold,
                            color = if (isNoirAurum) GoldBright else MaterialTheme.colorScheme.onSurface,
                        )
                        val balance = authState.user?.balance ?: 0.0
                        // Noir Aurum：余额用暗金 GoldDeep
                        Text(
                            text = "余额 ¥${formatDouble(balance)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isNoirAurum) GoldDeep else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        authViewModel.logout()
                        onLoggedOut()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "退出登录",
                            tint = if (isNoirAurum) GoldDeep else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 功能卡片网格
            item {
                Text(
                    text = "功能",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isNoirAurum) FontWeight.ExtraLight else FontWeight.SemiBold,
                    color = if (isNoirAurum) GoldBright else MaterialTheme.colorScheme.onSurface,
                )
            }
            item {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.height(260.dp),
                ) {
                    item {
                        FeatureCard(
                            title = "灵感",
                            subtitle = "与团团对话，激发摄影灵感",
                            icon = Icons.AutoMirrored.Filled.Chat,
                            onClick = {
                                chatViewModel.createNewSession { id ->
                                    onNavigateChat(id)
                                }
                            },
                        )
                    }
                    item {
                        FeatureCard(
                            title = "修图",
                            subtitle = "一键修图、去背景、超分辨率",
                            icon = Icons.Default.Image,
                            onClick = onNavigateImageEdit,
                        )
                    }
                    item {
                        FeatureCard(
                            title = "更多",
                            subtitle = "敬请期待",
                            icon = Icons.Default.AutoAwesome,
                            onClick = {},
                            enabled = false,
                        )
                    }
                }
            }

            // 最近会话
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "最近会话",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isNoirAurum) FontWeight.ExtraLight else FontWeight.SemiBold,
                        color = if (isNoirAurum) GoldBright else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            val recentSessions = chatState.sessions.take(5)
            if (recentSessions.isEmpty()) {
                item {
                    Text(
                        text = "还没有会话，点击「灵感」开始第一次对话吧～",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isNoirAurum) {
                            GoldDeep.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(recentSessions) { session ->
                    SessionRowItem(
                        title = session.title,
                        subtitle = formatSessionTime(session.updatedAt),
                        onClick = { onNavigateChat(session.id) },
                    )
                }
            }
        }
    }
}

/**
 * 功能卡片（Noir Aurum 黑曜鎏金风格）。
 *
 * 设计规范：
 * - ObsidianSurface 背景 + 1dp 香槟金描边（alpha 0.2）
 * - 20dp 大圆角
 * - 图标：淡金底（ChampagneGold alpha 0.15）+ 高光金图标（GoldBright）
 * - 标题 GoldBright ExtraLight，描述 GoldDeep
 * - 禁用态：降低透明度
 *
 * @param enabled 是否启用。false 时降低视觉权重（用于"更多"等未上线功能）。
 */
@Composable
private fun FeatureCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val isNoirAurum = LocalThemeStyle.current == LxThemeStyle.NOIR_AURUM
    val cardShape = RoundedCornerShape(20.dp)
    Card(
        modifier = Modifier
            .height(120.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .then(
                if (isNoirAurum) {
                    Modifier.border(
                        width = 1.dp,
                        color = ChampagneGold.copy(alpha = 0.2f),
                        shape = cardShape,
                    )
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isNoirAurum) {
                ObsidianSurface
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ),
        shape = cardShape,
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (enabled && !isNoirAurum) 1.dp else 0.dp,
        ),
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize()
                .alpha(if (enabled) 1f else 0.5f),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 图标：Noir Aurum 用淡金底（alpha 0.15）+ 高光金图标；Quiet Materiality 用深绿底 + 白图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isNoirAurum) {
                            ChampagneGold.copy(alpha = 0.15f)
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (isNoirAurum) GoldBright else Color.White,
                )
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isNoirAurum) FontWeight.ExtraLight else FontWeight.SemiBold,
                    color = if (isNoirAurum) GoldBright else MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isNoirAurum) {
                        GoldDeep
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    },
                )
            }
        }
    }
}

/**
 * 会话列表项（Noir Aurum 黑曜鎏金风格）。
 *
 * 设计规范：
 * - ObsidianSurfaceVariant 背景 + 1dp 香槟金描边（alpha 0.2）
 * - 14dp 圆角
 * - 标题 GoldBright Light + 友好时间格式 GoldDeep
 */
@Composable
private fun SessionRowItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val isNoirAurum = LocalThemeStyle.current == LxThemeStyle.NOIR_AURUM
    val cardShape = RoundedCornerShape(14.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                if (isNoirAurum) {
                    Modifier.border(
                        width = 1.dp,
                        color = ChampagneGold.copy(alpha = 0.2f),
                        shape = cardShape,
                    )
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isNoirAurum) {
                ObsidianSurfaceVariant
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        shape = cardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isNoirAurum) FontWeight.Light else FontWeight.Medium,
                color = if (isNoirAurum) GoldBright else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isNoirAurum) GoldDeep else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
