package cn.yhjyhj.weaver.core.approval;

/** 用户为当前会话选择的权限模式。 */
public enum PermissionMode {
    /** 规则表 + 回滚性；ASK 时请求用户。 */
    DEFAULT,

    /** 在 DEFAULT 基础上，RECOVERABLE 的 ASK 自动放行。 */
    ACCEPT_EDITS,

    /** 只读模式：非 REVERSIBLE 一律 DENY。 */
    PLAN
}
