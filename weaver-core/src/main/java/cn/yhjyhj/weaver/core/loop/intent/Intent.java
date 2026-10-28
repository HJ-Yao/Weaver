package cn.yhjyhj.weaver.core.loop.intent;

/** 用户意图；决定本轮走向。 */
public enum Intent {
    /** 普通对话，不强制使用工具。 */
    CHAT,
    /** 需要读取文件、执行命令、操作 git 等。 */
    TOOL_USE,
    /** 输入信息不足，需要向用户追问。 */
    CLARIFY
}
