package cn.yhjyhj.weaver.core.approval;

/** 记录本会话内已批准的「工具 + 操作」。实现必须线程安全。 */
public interface ApprovalGrantStore {

    /**
     * 判断指定会话是否已授权该工具操作。
     *
     * @param sessionId 会话标识
     * @param toolName 工具名称
     * @param operation 工具操作；无操作时为 {@code "*"}
     * @return 已授权时返回 true
     */
    boolean isGranted(String sessionId, String toolName, String operation);

    /**
     * 记录一条本会话授权。
     *
     * @param sessionId 会话标识
     * @param toolName 工具名称
     * @param operation 工具操作；无操作时使用 {@code "*"}
     */
    void grant(String sessionId, String toolName, String operation);

    /**
     * 创建不记录任何授权的空实现。
     *
     * @return 始终返回未授权的存储
     */
    static ApprovalGrantStore none() {
        return new ApprovalGrantStore() {
            @Override
            public boolean isGranted(
                    String sessionId, String toolName, String operation) {
                return false;
            }

            @Override
            public void grant(
                    String sessionId, String toolName, String operation) {}
        };
    }
}
