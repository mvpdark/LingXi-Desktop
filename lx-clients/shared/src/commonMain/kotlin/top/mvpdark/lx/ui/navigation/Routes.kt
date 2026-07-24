package top.mvpdark.lx.ui.navigation

/**
 * 路由常量。
 */
object Routes {
    /** 登录页。 */
    const val LOGIN = "login"

    /** 首页。 */
    const val HOME = "home"

    /** 聊天页，带 sessionId 参数。 */
    const val CHAT = "chat/{sessionId}"

    /** 图像编辑页。 */
    const val IMAGE_EDIT = "image_edit"

    /** 设置页。 */
    const val SETTINGS = "settings"

    /** 由 sessionId 拼装聊天页完整路由。 */
    fun chat(sessionId: String): String = "chat/$sessionId"

    /** 从路由参数中解析 sessionId。 */
    const val SESSION_ID_ARG = "sessionId"
}
