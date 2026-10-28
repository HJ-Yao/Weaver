package cn.yhjyhj.weaver.core.loop.node;

import cn.yhjyhj.weaver.core.approval.ApprovalDecision;
import cn.yhjyhj.weaver.core.approval.ApprovalGate;
import cn.yhjyhj.weaver.core.approval.ApprovalPolicy;
import cn.yhjyhj.weaver.core.approval.RuleBasedApprovalPolicy;
import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.loop.chain.AbstractRunNode;
import cn.yhjyhj.weaver.core.loop.chain.StrategyHandler;
import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.loop.context.RunRequest;
import cn.yhjyhj.weaver.core.loop.context.StepResult;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import cn.yhjyhj.weaver.core.tool.Tool;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 执行本步收集到的工具调用，并为每次调用记录开始与完成事实。 */
public final class ActNode extends AbstractRunNode {

    private final ApprovalPolicy approvalPolicy; // 计算每次调用的审批结论
    private final ApprovalGate approvalGate; // 在 ASK 时阻塞等待用户答复

    private final ToolRegistry registry; // 按模型工具名查找执行实例

    /**
     * 创建工具执行节点。
     *
     * @param registry 工具注册表
     * @param observeNode 工具执行完成后进入的结果收敛节点
     */
    public ActNode(
            ToolRegistry registry,
            StrategyHandler<RunRequest, RunContext, StepResult> observeNode) {
        this(
                registry,
                observeNode,
                RuleBasedApprovalPolicy.defaults(),
                ApprovalGate.denyAll());
    }

    /**
     * 创建带审批控制的工具执行节点。
     *
     * @param registry 工具注册表
     * @param observeNode 工具执行完成后进入的结果收敛节点
     * @param approvalPolicy 工具调用审批策略
     * @param approvalGate ASK 时等待用户答复的闸门
     */
    public ActNode(
            ToolRegistry registry,
            StrategyHandler<RunRequest, RunContext, StepResult> observeNode,
            ApprovalPolicy approvalPolicy,
            ApprovalGate approvalGate) {
        super(observeNode);
        this.registry = Objects.requireNonNull(registry, "registry");
        this.approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        this.approvalGate = Objects.requireNonNull(approvalGate, "approvalGate");
    }

    /**
     * 审批并执行本步模型请求的全部工具调用，再按原调用顺序记录结果。
     *
     * <p>输入是 {@link RunContext#getPendingToolCalls()}，每个调用的工具名和原始参数会通过
     * 注册表、审批策略和审批门完成风险判断。对每次调用都会 emit 一条
     * {@link SessionEvent.ToolCallStarted}；未直接允许的调用还会 emit 审批请求与结果，
     * 被拒绝或未知的工具生成错误结果而不执行。
     *
     * <p>产出是按模型给出顺序排列的 {@link SessionEvent.ToolCallCompleted} 事件。获得批准且
     * 已注册的调用在全部可并行时用虚拟线程并发执行，否则串行执行；并发完成顺序不会改变事件
     * 顺序。结束时清空待执行列表并标记 {@code toolExecuted}，使 ObserveNode 决定进入下一推理步。
     *
     * <p>失败处理：单个工具返回 null、抛出运行时异常或不存在时，都转换为带错误标记的
     * ToolResult 并继续记录完成事件；中断会恢复线程中断标记。审批或并行执行基础设施自身
     * 抛出的未收敛异常交给 RunLoop 统一终止本轮。
     */
    @Override
    protected StepResult doApply(RunRequest request, RunContext context) {
        List<StreamChunk.ToolCall> calls = List.copyOf(context.getPendingToolCalls());
        List<Tool> tools = calls.stream()
                .map(call -> registry.find(call.toolName()).orElse(null))
                .toList();
        List<ToolResult> results = new ArrayList<>(calls.size());
        for (int index = 0; index < calls.size(); index++) {
            results.add(null);
        }
        boolean[] approved = new boolean[calls.size()];

        for (int index = 0; index < calls.size(); index++) {
            StreamChunk.ToolCall call = calls.get(index);
            Reversibility reversibility =
                    reversibilityOf(tools.get(index), call.argumentsJson());
            ApprovalDecision decision = approvalPolicy.decide(call, reversibility);
            approved[index] = approve(call, reversibility, decision, context);
            if (!approved[index]) {
                String deniedBy = decision == ApprovalDecision.DENY ? "policy" : "user";
                results.set(index, new ToolResult("denied by " + deniedBy, true, 0L));
            }
            context.getSink().emit(new SessionEvent.ToolCallStarted(
                    call.callId(), call.toolName(), call.argumentsJson()));
        }

        List<Integer> executable = executableCallIndexes(tools, approved);
        if (shouldExecuteInParallel(calls, tools, executable)) {
            executeInParallel(calls, tools, executable, results);
        } else {
            executeSerially(calls, tools, executable, results);
        }
        for (int index = 0; index < calls.size(); index++) {
            if (approved[index] && tools.get(index) == null) {
                results.set(index, unknownTool(calls.get(index)));
            }
        }

        // 并行完成顺序不影响事实顺序；结果始终按模型给出的调用顺序落库。
        for (int index = 0; index < calls.size(); index++) {
            StreamChunk.ToolCall call = calls.get(index);
            context.getSink().emit(new SessionEvent.ToolCallCompleted(
                    call.callId(), results.get(index)));
        }
        context.getPendingToolCalls().clear();
        context.setToolExecuted(true);
        return null;
    }

    private boolean approve(
            StreamChunk.ToolCall call,
            Reversibility reversibility,
            ApprovalDecision decision,
            RunContext context) {
        if (decision == ApprovalDecision.ALLOW) {
            return true;
        }

        context.getSink().emit(new SessionEvent.ApprovalRequested(
                call.callId(), call.toolName(), reversibility));
        if (decision == ApprovalDecision.DENY) {
            context.getSink().emit(
                    new SessionEvent.ApprovalResolved(call.callId(), false, "policy"));
            return false;
        }

        boolean approved = approvalGate.requestApproval(call, reversibility);
        context.getSink().emit(new SessionEvent.ApprovalResolved(
                call.callId(), approved, approved ? "user" : "user-denied"));
        return approved;
    }

    private static Reversibility reversibilityOf(Tool tool, String argumentsJson) {
        if (tool == null) {
            return Reversibility.IRREVERSIBLE;
        }
        Reversibility reversibility = tool.reversibility(argumentsJson);
        // 工具返回空声明时不能获得更宽松的审批待遇。
        return reversibility == null ? Reversibility.IRREVERSIBLE : reversibility;
    }

    private static List<Integer> executableCallIndexes(List<Tool> tools, boolean[] approved) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < tools.size(); index++) {
            if (approved[index] && tools.get(index) != null) {
                indexes.add(index);
            }
        }
        return indexes;
    }

    private boolean shouldExecuteInParallel(
            List<StreamChunk.ToolCall> calls,
            List<Tool> tools,
            List<Integer> executable) {
        if (executable.size() <= 1) {
            return false;
        }
        return executable.stream()
                .allMatch(index -> tools.get(index)
                        .parallelizable(calls.get(index).argumentsJson()));
    }

    private void executeSerially(
            List<StreamChunk.ToolCall> calls,
            List<Tool> tools,
            List<Integer> executable,
            List<ToolResult> results) {
        for (int index : executable) {
            results.set(index, executeOne(calls.get(index), tools.get(index)));
        }
    }

    private void executeInParallel(
            List<StreamChunk.ToolCall> calls,
            List<Tool> tools,
            List<Integer> executable,
            List<ToolResult> results) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ToolResult>> futures = new ArrayList<>(executable.size());
            for (int index : executable) {
                futures.add(executor.submit(
                        () -> executeOne(calls.get(index), tools.get(index))));
            }

            for (int offset = 0; offset < executable.size(); offset++) {
                results.set(executable.get(offset), await(futures.get(offset)));
            }
        }
    }

    private ToolResult executeOne(StreamChunk.ToolCall call, Tool tool) {
        if (tool == null) {
            return unknownTool(call);
        }

        long startedAt = System.nanoTime();
        try {
            ToolResult result = tool.execute(call.argumentsJson());
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            if (result == null) {
                return new ToolResult("tool returned null: " + call.toolName(), true, durationMs);
            }
            return new ToolResult(result.content(), result.isError(), durationMs);
        } catch (RuntimeException exception) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            return new ToolResult(
                    "tool execution failed: " + exception.getMessage(), true, durationMs);
        }
    }

    private static ToolResult unknownTool(StreamChunk.ToolCall call) {
        return new ToolResult("unknown tool: " + call.toolName(), true, 0L);
    }

    private ToolResult await(Future<ToolResult> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new ToolResult("tool execution interrupted", true, 0L);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            return new ToolResult("tool execution failed: " + cause.getMessage(), true, 0L);
        }
    }
}
