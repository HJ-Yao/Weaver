package cn.yhjyhj.weaver.core.loop.node;

/**
 * 一次工具调用的审批结论。
 *
 * @param approved 是否允许执行
 * @param reason 拒绝原因；允许时为 null
 */
record ApprovalOutcome(boolean approved, String reason) {

    /** 创建允许执行的结论。 */
    static ApprovalOutcome allow() {
        return new ApprovalOutcome(true, null);
    }

    /**
     * 创建拒绝执行的结论。
     *
     * @param reason 写入工具结果的稳定拒绝原因
     * @return 拒绝结论
     */
    static ApprovalOutcome deny(String reason) {
        return new ApprovalOutcome(false, reason);
    }
}
