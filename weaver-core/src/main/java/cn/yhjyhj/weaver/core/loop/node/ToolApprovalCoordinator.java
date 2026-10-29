package cn.yhjyhj.weaver.core.loop.node;

import cn.yhjyhj.weaver.core.approval.ApprovalContext;
import cn.yhjyhj.weaver.core.approval.ApprovalDecision;
import cn.yhjyhj.weaver.core.approval.ApprovalGate;
import cn.yhjyhj.weaver.core.approval.ApprovalGrantStore;
import cn.yhjyhj.weaver.core.approval.ApprovalPolicy;
import cn.yhjyhj.weaver.core.approval.ApprovalResult;
import cn.yhjyhj.weaver.core.approval.ApprovalRules;
import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import cn.yhjyhj.weaver.core.tool.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** 对一批工具调用执行审批，并记录审批事件。 */
final class ToolApprovalCoordinator {

    private final ApprovalPolicy policy; // 计算单次调用的审批结论
    private final ApprovalGate gate; // ASK 时向用户请求批准
    private final ApprovalGrantStore grants; // 保存会话级工具操作授权

    /**
     * 创建审批协调器。
     *
     * @param policy 工具调用审批策略
     * @param gate ASK 时等待用户答复的闸门
     * @param grants 当前会话的授权记录
     */
    ToolApprovalCoordinator(
            ApprovalPolicy policy,
            ApprovalGate gate,
            ApprovalGrantStore grants) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.grants = Objects.requireNonNull(grants, "grants");
    }

    /**
     * 按调用顺序审批，并返回与 calls 等长的结论列表。
     *
     * <p>未知工具按不可回滚审批；审批事件严格按调用顺序写入事件出口。
     *
     * @param calls 待审批工具调用
     * @param tools 与 calls 下标对应的已解析工具，未知工具为 null
     * @param context 当前运行上下文
     * @return 与 calls 等长且同序的审批结论
     */
    List<ApprovalOutcome> approveAll(
            List<StreamChunk.ToolCall> calls,
            List<Tool> tools,
            RunContext context) {
        return approveAll(calls, tools, context, call -> {});
    }

    /**
     * 按调用顺序审批，并在每次审批事件记录后通知调用方。
     *
     * <p>回调用于把工具开始事件插在对应调用的审批事件之后，保持既有事件顺序。
     *
     * @param calls 待审批工具调用
     * @param tools 与 calls 下标对应的已解析工具
     * @param context 当前运行上下文
     * @param afterApproval 单次审批事件记录后的回调
     * @return 与 calls 等长且同序的审批结论
     */
    List<ApprovalOutcome> approveAll(
            List<StreamChunk.ToolCall> calls,
            List<Tool> tools,
            RunContext context,
            Consumer<StreamChunk.ToolCall> afterApproval) {
        Objects.requireNonNull(calls, "calls");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(afterApproval, "afterApproval");
        if (calls.size() != tools.size()) {
            throw new IllegalArgumentException("calls and tools must have equal size");
        }

        List<ApprovalOutcome> outcomes = new ArrayList<>(calls.size());
        for (int index = 0; index < calls.size(); index++) {
            StreamChunk.ToolCall call = calls.get(index);
            outcomes.add(approveOne(call, tools.get(index), context));
            afterApproval.accept(call);
        }
        return List.copyOf(outcomes);
    }

    private ApprovalOutcome approveOne(
            StreamChunk.ToolCall call,
            Tool tool,
            RunContext context) {
        Reversibility reversibility = reversibilityOf(tool, call.argumentsJson());
        ApprovalResult result = policy.decide(
                call,
                reversibility,
                new ApprovalContext(
                        context.getSessionId(), gate.isInteractive(), grants));

        return switch (result.decision()) {
            case ALLOW -> resolveAllow(call, result, context);
            case DENY -> resolveDeny(call, reversibility, result, context);
            case ASK -> resolveAsk(call, reversibility, context);
        };
    }

    private ApprovalOutcome resolveAllow(
            StreamChunk.ToolCall call,
            ApprovalResult result,
            RunContext context) {
        context.getSink().emit(new SessionEvent.ApprovalResolved(
                call.callId(), true, result.reason()));
        return ApprovalOutcome.allow();
    }

    private ApprovalOutcome resolveDeny(
            StreamChunk.ToolCall call,
            Reversibility reversibility,
            ApprovalResult result,
            RunContext context) {
        emitRequested(call, reversibility, context);
        context.getSink().emit(new SessionEvent.ApprovalResolved(
                call.callId(), false, result.reason()));
        return ApprovalOutcome.deny(result.reason());
    }

    private ApprovalOutcome resolveAsk(
            StreamChunk.ToolCall call,
            Reversibility reversibility,
            RunContext context) {
        emitRequested(call, reversibility, context);
        boolean approved = gate.requestApproval(call, reversibility);
        if (approved) {
            grant(call, context);
        }

        String reason = approved ? "user" : "user-denied";
        context.getSink().emit(new SessionEvent.ApprovalResolved(
                call.callId(), approved, reason));
        return approved ? ApprovalOutcome.allow() : ApprovalOutcome.deny(reason);
    }

    private void grant(StreamChunk.ToolCall call, RunContext context) {
        String operation = ApprovalRules.operationOf(call.argumentsJson());
        grants.grant(
                context.getSessionId(),
                call.toolName(),
                operation == null ? "*" : operation);
    }

    private static void emitRequested(
            StreamChunk.ToolCall call,
            Reversibility reversibility,
            RunContext context) {
        context.getSink().emit(new SessionEvent.ApprovalRequested(
                call.callId(), call.toolName(), reversibility));
    }

    private static Reversibility reversibilityOf(Tool tool, String argumentsJson) {
        if (tool == null) {
            return Reversibility.IRREVERSIBLE;
        }
        Reversibility reversibility = tool.reversibility(argumentsJson);
        // 工具返回空声明时不能获得更宽松的审批待遇。
        return reversibility == null ? Reversibility.IRREVERSIBLE : reversibility;
    }
}
