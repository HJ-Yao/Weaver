package cn.yhjyhj.weaver.core.approval;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 使用并发映射保存会话授权的进程内实现。 */
public final class InMemoryApprovalGrantStore implements ApprovalGrantStore {

    /** 已授权键集合；值仅占位，存在即表示授权。 */
    private final ConcurrentHashMap<Key, Boolean> grants = new ConcurrentHashMap<>();

    @Override
    public boolean isGranted(
            String sessionId, String toolName, String operation) {
        return grants.containsKey(new Key(sessionId, toolName, operation));
    }

    @Override
    public void grant(String sessionId, String toolName, String operation) {
        grants.put(new Key(sessionId, toolName, operation), Boolean.TRUE);
    }

    /**
     * 授权粒度键。
     *
     * @param sessionId 会话标识
     * @param toolName 工具名称
     * @param operation 工具操作
     */
    private record Key(String sessionId, String toolName, String operation) {

        /** 禁止任一授权维度为空，避免形成无法命中的隐式通配授权。 */
        private Key {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(operation, "operation");
        }
    }
}
