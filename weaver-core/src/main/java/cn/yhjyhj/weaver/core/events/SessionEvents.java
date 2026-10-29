package cn.yhjyhj.weaver.core.events;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯函数折叠器：把事件流重放成会话快照。
 *
 * 是 Weaver "回放 == 现场" 的一等公民：任何视图都应从这里推导，而不是另存状态。
 */
public final class SessionEvents {

    private SessionEvents() {}

    /** 按事件顺序折叠；事件序号单调、可任意起止。 */
    public static SessionSnapshot replay(Iterable<StoredEvent> events) {
        List<SessionSnapshot.Message> messages = new ArrayList<>();
        List<SessionSnapshot.ToolCallRecord> toolCalls = new ArrayList<>();
        List<SessionSnapshot.ApprovalRecord> approvals = new ArrayList<>();
        Map<String, SessionEvent.ToolCallStarted> pendingToolCalls = new HashMap<>();
        Map<String, SessionEvent.ApprovalRequested> pendingApprovalRequests =
                new HashMap<>();
        boolean aborted = false;
        long lastSeq = 0L;

        for (StoredEvent stored : events) {
            lastSeq = Math.max(lastSeq, stored.seq());
            SessionEvent e = stored.event();
            if (e instanceof SessionEvent.MessageAdded m) {
                messages.add(new SessionSnapshot.Message(m.role(), m.content()));
            } else if (e instanceof SessionEvent.ToolCallStarted t) {
                pendingToolCalls.put(t.callId(), t);
            } else if (e instanceof SessionEvent.ToolCallCompleted c) {
                SessionEvent.ToolCallStarted started = pendingToolCalls.remove(c.callId());
                toolCalls.add(new SessionSnapshot.ToolCallRecord(
                        c.callId(),
                        started == null ? "unknown" : started.toolName(),
                        started == null ? "" : started.argumentsJson(),
                        c.result()));
                // 工具结果也是下一轮模型可消费的事实，按完成顺序进入消息历史。
                messages.add(new SessionSnapshot.Message(
                        SessionEvent.Role.TOOL, c.result().content()));
            } else if (e instanceof SessionEvent.ApprovalRequested r) {
                pendingApprovalRequests.put(r.callId(), r);
            } else if (e instanceof SessionEvent.ApprovalResolved r) {
                SessionEvent.ApprovalRequested requested =
                        pendingApprovalRequests.remove(r.callId());
                approvals.add(new SessionSnapshot.ApprovalRecord(
                        r.callId(),
                        requested == null ? "unknown" : requested.toolName(),
                        r.approved(),
                        r.reason()));
            } else if (e instanceof SessionEvent.RunFinished f) {
                aborted = f.aborted();
            }
        }
        return new SessionSnapshot(
                List.copyOf(messages),
                List.copyOf(toolCalls),
                List.copyOf(approvals),
                aborted,
                lastSeq);
    }
}
