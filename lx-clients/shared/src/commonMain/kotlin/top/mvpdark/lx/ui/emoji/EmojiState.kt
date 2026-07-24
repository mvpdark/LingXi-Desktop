package top.mvpdark.lx.ui.emoji

/**
 * 团团表情状态。
 *
 * 移植自旧版 Web 项目 chat.js 的表情状态机。
 * 每个状态对应一个 APNG 动画文件。
 *
 * 状态流转：
 * - idle → thinking（路由开始）
 * - thinking → working（分发到子 Agent）
 * - working → happy（完成，3 秒后回 idle）
 * - working → apologizing（出错/违禁内容，3 秒后回 idle）
 * - 任意 → idle（连接关闭）
 */
enum class EmojiState(val resourcePath: String) {
    /** 待机状态。 */
    IDLE("files/emoji/animated/idle.apng"),

    /** 思考中（路由分析阶段）。 */
    THINKING("files/emoji/animated/thinking.apng"),

    /** 工作中（Agent 分发处理阶段）。旧版用 meeting.apng。 */
    WORKING("files/emoji/animated/meeting.apng"),

    /** 道歉（出错或内容违规）。3 秒后自动回 IDLE。 */
    APOLOGIZING("files/emoji/animated/apologizing.apng"),

    /** 开心（回复完成）。3 秒后自动回 IDLE。 */
    HAPPY("files/emoji/animated/happy.apng"),
}

/** 子 Agent 表情状态（资源路径由 [getAgentEmojiPath] 按状态生成）。 */
enum class AgentEmojiState {
    /** 待机（不显示表情）。 */
    IDLE,

    /** 工作中。 */
    WORKING,

    /** 完成。 */
    DONE,

    /** 出错。 */
    ERROR,
}

/**
 * 子 Agent 类型及其表情资源路径。
 */
enum class AgentType(val displayName: String, val emojiDir: String) {
    COMPOSITION_ADVISOR("构图顾问", "composition_advisor"),
    COLOR_GRADER("调色专家", "color_grader"),
    LIGHTING_ANALYST("光线分析师", "lighting_analyst"),
    RETOUCH_ADVISOR("修图顾问", "retouch_advisor"),
    PHOTO_ANALYST("照片分析师", "photo_analyst"),
    IMAGE_ENHANCER("图效师", "image_enhancer"),
    ;

    companion object {
        /** 根据 Agent 名称模糊匹配类型。 */
        fun fromName(name: String): AgentType? {
            return entries.firstOrNull { type ->
                name.contains(type.displayName) ||
                    name.contains(type.emojiDir, ignoreCase = true) ||
                    name.contains(type.name, ignoreCase = true)
            }
        }
    }
}

/**
 * 获取子 Agent 表情的资源路径。
 *
 * @param agentType Agent 类型。
 * @param state 表情状态。
 * @return 资源路径，或 null（idle 状态不显示）。
 */
fun getAgentEmojiPath(agentType: AgentType, state: AgentEmojiState): String? {
    return when (state) {
        AgentEmojiState.IDLE -> null
        AgentEmojiState.WORKING -> "files/emoji/agents/animated/${agentType.emojiDir}/working.apng"
        AgentEmojiState.DONE -> "files/emoji/agents/animated/${agentType.emojiDir}/done.apng"
        AgentEmojiState.ERROR -> "files/emoji/agents/animated/${agentType.emojiDir}/error.apng"
    }
}

/**
 * 智能选择完成表情。
 *
 * 移植自 chat.js 的 chooseDoneEmoji 逻辑。
 * 根据团团的回复内容判断应该显示 happy 还是 apologizing。
 *
 * @param text 团团的回复文本。
 * @return HAPPY 或 APOLOGIZING。
 */
fun chooseDoneEmoji(text: String): EmojiState {
    val lowerText = text.lowercase()

    // 安全相关关键词 → 道歉
    val securityKeywords = listOf(
        "喊110", "api key", "api_key", "apikey", "密钥", "token", "secret",
        "猫娘小助理团团呀", "不能聊", "开心的话题",
        "正经的猫娘", "只回答摄影",
    )
    if (securityKeywords.any { lowerText.contains(it.lowercase()) }) {
        // 进一步判断：如果是短回复 + 身份保护关键词
        if (text.length < 50 && (
                    lowerText.contains("猫娘小助理团团呀") ||
                            lowerText.contains("只回答摄影")
                )
        ) {
            return EmojiState.APOLOGIZING
        }
        if (lowerText.contains("api key") || lowerText.contains("密钥") || lowerText.contains("token")) {
            return EmojiState.APOLOGIZING
        }
        if (lowerText.contains("不能聊") || lowerText.contains("开心的话题")) {
            return EmojiState.APOLOGIZING
        }
        if (lowerText.contains("正经的猫娘") || lowerText.contains("只回答摄影")) {
            return EmojiState.APOLOGIZING
        }
    }

    // 包含图片生成标记 → 开心
    if (text.contains("[IMAGE]") || text.contains("[image]")) {
        return EmojiState.HAPPY
    }

    // 默认 → 开心
    return EmojiState.HAPPY
}
