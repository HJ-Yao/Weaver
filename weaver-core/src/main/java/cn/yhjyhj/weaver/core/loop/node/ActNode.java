package cn.yhjyhj.weaver.core.loop.node;

import cn.yhjyhj.weaver.core.approval.ApprovalGate;
import cn.yhjyhj.weaver.core.approval.ApprovalGrantStore;
import cn.yhjyhj.weaver.core.approval.ApprovalPolicy;
import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.loop.chain.AbstractRunNode;
import cn.yhjyhj.weaver.core.loop.chain.StrategyHandler;
import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.loop.context.RunRequest;
import cn.yhjyhj.weaver.core.loop.context.StepResult;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Tool;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import java.util.List;

/** 执行本步收集到的工具调用，并记录审批、开始与完成事实。 */
public final class ActNode extends AbstractRunNode {

    private final ToolApprovalCoordinator approvalCoordinator; // 批量审批并记录审批事件
    private final ToolExecutor toolExecutor; // 解析并执行已批准的工具调用

    /**
     * 创建带审批控制的工具执行节点。
     *
     * @param registry 工具注册表
     * @param approvalPolicy 工具调用审批策略
     * @param approvalGate ASK 时等待用户答复的闸门
     * @param approvalGrants 当前会话的授权记录
     * @param next 工具执行完成后进入的下一节点
     */
    public ActNode(
            ToolRegistry registry,
            ApprovalPolicy approvalPolicy,
            ApprovalGate approvalGate,
            ApprovalGrantStore approvalGrants,
            StrategyHandler<RunRequest, RunContext, StepResult> next) {
        super(next);
        this.approvalCoordinator = new ToolApprovalCoordinator(
                approvalPolicy, approvalGate, approvalGrants);
        this.toolExecutor = new ToolExecutor(registry);
    }

    /**
     * 审批并执行本步全部工具调用，再按原始调用顺序记录完成结果。
     *
     * <p>输入来自 {@link RunContext#getPendingToolCalls()}。本节点先按调用顺序完成
     * 审批并记录审批事实，再记录每个调用的开始事件；执行器完成并发调度和单调用异常
     * 收敛后，本节点按 calls 顺序记录完成事件。最后清空待执行列表并将本步标记为已执行，
     * 单个调用失败不会中断同批其他结果。
     *
     * @param request 当前步骤请求
     * @param context 当前步骤上下文
     * @return 固定返回 null，以继续策略链
     */
    @Override
    protected StepResult doApply(RunRequest request, RunContext context) {
        List<StreamChunk.ToolCall> calls = List.copyOf(context.getPendingToolCalls());
        List<Tool> tools = toolExecutor.resolve(calls);
        List<ApprovalOutcome> outcomes = approvalCoordinator.approveAll(
                calls, tools, context, call -> emitStarted(call, context));
        List<ToolResult> results = toolExecutor.execute(calls, tools, outcomes);
        emitCompleted(calls, results, context);
        context.getPendingToolCalls().clear();
        context.setToolExecuted(true);
        return null;
    }

    private static void emitStarted(
            StreamChunk.ToolCall call,
            RunContext context) {
        context.getSink().emit(new SessionEvent.ToolCallStarted(
                call.callId(), call.toolName(), call.argumentsJson()));
    }

    private static void emitCompleted(
            List<StreamChunk.ToolCall> calls,
            List<ToolResult> results,
            RunContext context) {
        for (int index = 0; index < calls.size(); index++) {
            context.getSink().emit(new SessionEvent.ToolCallCompleted(
                    calls.get(index).callId(), results.get(index)));
        }
    }
}
