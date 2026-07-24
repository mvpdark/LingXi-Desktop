package top.mvpdark.lx.ui.components

import androidx.compose.runtime.Composable

/**
 * 沉浸式系统栏效果（expect）。
 * active=true 时隐藏状态栏与导航栏（Android 行为，Desktop/iOS no-op）；
 * 离开 Composition 或 active=false 时自动恢复。
 */
@Composable
expect fun ImmersiveSystemBarsEffect(active: Boolean)
