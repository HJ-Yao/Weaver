package cn.yhjyhj.weaver.core.events;

import cn.yhjyhj.weaver.core.model.ModelMessage;
import cn.yhjyhj.weaver.core.model.StreamChunk;
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

    /**
     * 按事件顺序重建模型请求历史；保留 assistant 的工具调用与 tool 结果的 callId 配对。
     *
     * <p>用户与助手文本按事件顺序进入历史。并行工具调用归入同一个 assistant 消息；
     * 纯工具调用轮次没有 assistant 文本事件时补一条空文本消息。工具结果按完成顺序追加，
     * 审批与运行结束事件不影响模型历史。
     *
     * @param events 已存储的会话事件
     * @return 不可变的模型历史
     */
    public static List<ModelMessage> replayModelHistory(Iterable<StoredEvent> events) {
        List<ModelMessage> history = new ArrayList<>();
        int assistantIndex = -1;

        for (StoredEvent stored : events) {
            SessionEvent event = stored.event();
            if (event instanceof SessionEvent.MessageAdded message) {
                if (message.role() == SessionEvent.Role.USER) {
                    history.add(new ModelMessage.User(message.content()));
                    // 新用户轮开始后，后续工具调用不能挂到上一轮的 assistant 消息上。
                    assistantIndex = -1;
                } else if (message.role() == SessionEvent.Role.ASSISTANT) {
                    history.add(new ModelMessage.Assistant(message.content(), List.of()));
                    // 记下当前 assistant 的位置，后续 ToolCallStarted 会把调用补到这条消息。
                    assistantIndex = history.size() - 1;
                }
            } else if (event instanceof SessionEvent.ToolCallStarted started) {
                if (assistantIndex < 0) {
                    // 纯工具轮可能没有 assistant 文本事件；模型协议仍要求调用挂在 assistant 消息下。
                    history.add(new ModelMessage.Assistant("", List.of()));
                    assistantIndex = history.size() - 1;
                }
                ModelMessage.Assistant assistant =
                        (ModelMessage.Assistant) history.get(assistantIndex);
                List<StreamChunk.ToolCall> toolCalls = new ArrayList<>(assistant.toolCalls());
                // 并行调用的 Started 事件共享当前 assistant，必须按发起顺序追加到同一条消息。
                toolCalls.add(new StreamChunk.ToolCall(
                        started.callId(), started.toolName(), started.argumentsJson()));
                history.set(
                        assistantIndex,
                        new ModelMessage.Assistant(assistant.content(), toolCalls));
            } else if (event instanceof SessionEvent.ToolCallCompleted completed) {
                ToolResult result = completed.result();
                // 完成事件才提供可回传模型的输出；结果后要重新开始 assistant 归属。
                history.add(new ModelMessage.Tool(
                        completed.callId(), result.content(), result.isError()));
                assistantIndex = -1;
            }
            // 审批是控制面事件，RunFinished 只是运行边界，二者都不是模型消息。
        }
        return List.copyOf(history);
    }
}
