package cn.yhjyhj.weaver.core.events;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

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
        Deque<String> pendingArgs = new ArrayDeque<>();
        boolean aborted = false;
        long lastSeq = 0L;

        for (StoredEvent stored : events) {
            lastSeq = Math.max(lastSeq, stored.seq());
            SessionEvent e = stored.event();
            if (e instanceof SessionEvent.MessageAdded m) {
                messages.add(new SessionSnapshot.Message(m.role(), m.content()));
            } else if (e instanceof SessionEvent.ToolCallStarted t) {
                pendingArgs.addLast(t.argsSummary());
            } else if (e instanceof SessionEvent.ToolCallCompleted c) {
                // M1 线性执行：按开始顺序配对同名工具的调用。
                String args = pendingArgs.pollFirst();
                toolCalls.add(new SessionSnapshot.ToolCallRecord(
                        c.toolName(), args == null ? "" : args, c.resultSummary()));
            } else if (e instanceof SessionEvent.RunFinished f) {
                aborted = f.aborted();
            }
        }
        return new SessionSnapshot(List.copyOf(messages), List.copyOf(toolCalls), aborted, lastSeq);
    }
}