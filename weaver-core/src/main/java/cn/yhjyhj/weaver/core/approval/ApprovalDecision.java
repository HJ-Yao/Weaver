package cn.yhjyhj.weaver.core.approval;

/** 单次工具调用的基础审批结论。 */
public enum ApprovalDecision {
    /** 无需用户确认即可执行。 */
    ALLOW,

    /** 必须等待用户确认。 */
    ASK,

    /** 无论是否有用户界面都拒绝执行。 */
    DENY
}
