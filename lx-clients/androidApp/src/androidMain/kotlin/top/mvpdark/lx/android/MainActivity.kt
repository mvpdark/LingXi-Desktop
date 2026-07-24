package top.mvpdark.lx.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import top.mvpdark.lx.LxApp

/**
 * Android 主 Activity。
 *
 * - 继承 [ComponentActivity]
 * - [enableEdgeToEdge] 启用边到边布局
 * - [setContent] 渲染 [LxApp] 共享根组件
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LxApp()
        }
    }
}
