package cn.yhjyhj.weaver.core.loop;

import cn.yhjyhj.weaver.core.approval.ApprovalGate;
import cn.yhjyhj.weaver.core.approval.ApprovalPolicy;
import cn.yhjyhj.weaver.core.approval.RuleBasedApprovalPolicy;
import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.SessionEventStore;
import cn.yhjyhj.weaver.core.loop.chain.StrategyHandler;
import cn.yhjyhj.weaver.core.loop.context.EventSink;
import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.loop.context.RunRequest;
import cn.yhjyhj.weaver.core.loop.context.StepResult;
import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.model.StreamSink;
import cn.yhjyhj.weaver.core.phase.Phase;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * 直线驱动单步策略链的运行循环。
 *
 * <p>线程契约：一个实例同一时间只应执行一个 {@link #run(String, String)}；
 * 并发会话请各自创建实例。{@link #cancel()} 可以从其他线程调用。
 */
@Slf4j
public final class RunLoop {

    private final ApprovalPolicy approvalPolicy; // 工具调用审批策略
    private final ApprovalGate approvalGate; // ASK 时等待用户答复的闸门

    /** 单轮默认最大步数；达到上限按中止处理。 */
    public static final int DEFAULT_MAX_STEPS = 50;

    /** 未注入实时输出出口时使用的空实现。 */
    private static final StreamSink NO_OP_STREAM_SINK = new StreamSink() {
        @Override
        public void onDelta(String delta) {}

        @Override
        public void onToolCall(StreamChunk.ToolCall call) {}

        @Override
        public void onDone(StreamChunk.StopReason reason) {}
    };

    private final ModelClient model; // 模型调用边界
    private final SessionEventStore store; // 会话事实源
    private final int maxSteps; // 本次运行允许执行的最大步数
    private final StreamSink streamSink; // 模型文本与工具调用的实时输出出口
    private final ToolRegistry toolRegistry; // 本循环可执行的工具集合
    /** 当前运行是否收到取消请求；每次 run 开始时重置。 */
    private final AtomicBoolean cancelled = new AtomicBoolean();
    /** 最近一次运行收敛后的阶段；run 返回后由调用线程读取。 */
    private volatile Phase finalPhase;

    /**
     * 使用默认步数上限创建运行循环。
     *
     * @param model 模型调用边界
     * @param store 会话事件存储
     */
    public RunLoop(ModelClient model, SessionEventStore store) {
        this(model, store, DEFAULT_MAX_STEPS, NO_OP_STREAM_SINK, ToolRegistry.empty());
    }

    /**
     * 使用默认步数上限和指定工具注册表创建运行循环。
     *
     * @param model 模型调用边界
     * @param store 会话事件存储
     * @param toolRegistry 工具注册表
     */
    public RunLoop(ModelClient model, SessionEventStore store, ToolRegistry toolRegistry) {
        this(model, store, DEFAULT_MAX_STEPS, NO_OP_STREAM_SINK, toolRegistry);
    }

    /**
     * 使用默认步数上限、工具注册表与显式审批依赖创建运行循环。
     *
     * @param model 模型调用边界
     * @param store 会话事件存储
     * @param toolRegistry 工具注册表
     * @param approvalPolicy 工具调用审批策略
     * @param approvalGate ASK 时等待用户答复的闸门
     */
    public RunLoop(
            ModelClient model,
            SessionEventStore store,
            ToolRegistry toolRegistry,
            ApprovalPolicy approvalPolicy,
            ApprovalGate approvalGate) {
        this(
                model,
                store,
                DEFAULT_MAX_STEPS,
                NO_OP_STREAM_SINK,
                toolRegistry,
                approvalPolicy,
                approvalGate);
    }

    /**
     * 创建具有指定步数上限的运行循环。
     *
     * @param model 模型调用边界
     * @param store 会话事件存储
     * @param maxSteps 单轮最大步数
     */
    public RunLoop(ModelClient model, SessionEventStore store, int maxSteps) {
        this(model, store, maxSteps, NO_OP_STREAM_SINK, ToolRegistry.empty());
    }

    /**
     * 创建具有指定步数上限和工具注册表的运行循环。
     *
     * @param model 模型调用边界
     * @param store 会话事件存储
     * @param maxSteps 单轮最大步数
     * @param toolRegistry 工具注册表
     */
    public RunLoop(
            ModelClient model,
            SessionEventStore store,
            int maxSteps,
            ToolRegistry toolRegistry) {
        this(model, store, maxSteps, NO_OP_STREAM_SINK, toolRegistry);
    }

    /**
     * 创建具有指定步数上限和实时输出出口的运行循环。
     *
     * @param model 模型调用边界
     * @param store 会话事件存储
     * @param maxSteps 单轮最大步数
     * @param streamSink 模型文本与工具调用的实时输出出口
     */
    public RunLoop(
            ModelClient model,
            SessionEventStore store,
            int maxSteps,
            StreamSink streamSink) {
        this(model, store, maxSteps, streamSink, ToolRegistry.empty());
    }

    /**
     * 创建具有指定步数上限、实时输出出口和工具注册表的运行循环。
     *
     * @param model 模型调用边界
     * @param store 会话事件存储
     * @param maxSteps 单轮最大步数
     * @param streamSink 模型文本与工具调用的实时输出出口
     * @param toolRegistry 工具注册表
     */
    public RunLoop(
            ModelClient model,
            SessionEventStore store,
            int maxSteps,
            StreamSink streamSink,
            ToolRegistry toolRegistry) {
        this(
                model,
                store,
                maxSteps,
                streamSink,
                toolRegistry,
                RuleBasedApprovalPolicy.defaults(),
                ApprovalGate.denyAll());
    }

    /**
     * 创建具有完整运行参数与审批依赖的运行循环。
     *
     * @param model 模型调用边界
     * @param store 会话事件存储
     * @param maxSteps 单轮最大步数
     * @param streamSink 模型文本与工具调用的实时输出出口
     * @param toolRegistry 工具注册表
     * @param approvalPolicy 工具调用审批策略
     * @param approvalGate ASK 时等待用户答复的闸门
     */
    public RunLoop(
            ModelClient model,
            SessionEventStore store,
            int maxSteps,
            StreamSink streamSink,
            ToolRegistry toolRegistry,
            ApprovalPolicy approvalPolicy,
            ApprovalGate approvalGate) {
        this.model = model;
        this.store = store;
        this.maxSteps = maxSteps;
        this.streamSink = streamSink;
        this.toolRegistry = toolRegistry;
        this.approvalPolicy = approvalPolicy;
        this.approvalGate = approvalGate;
    }

    /**
     * 执行一轮会话，直到自然完成、达到步数上限、失败或收到取消请求。
     *
     * <p>输入是本轮用户文本 {@code input}、会话标识 {@code sessionId}，以及构造时装配的
     * 模型、事件存储、工具注册表和审批依赖。方法先持久化用户消息，再从 step 0 开始逐步
     * 执行策略链；每一步使用新的 {@link RunContext}，跨步事实只从事件存储回放。
     *
     * <p>产出是完整会话事件流：节点追加的 assistant/tool/approval 事件，以及最终一条
     * {@link SessionEvent.RunFinished}。运行结束后 {@link #finalPhase()} 暴露完成态或
     * 中止态，供调用方审计。
     *
     * <p>关键分支：单步结果声明 aborted、运行中收到取消，或执行完最后允许步数时转为中止；
     * 单步结果声明 finished 时自然完成；否则推进下一步。失败处理：策略链或模型流基础设施
     * 抛出的异常会被记录并收敛为 aborted，不向调用线程继续抛出，且始终写出终止事件。
     *
     * @param sessionId 当前会话标识
     * @param input 本轮用户输入
     */
    public void run(String sessionId, String input) {
        // 每次 run 是独立执行，必须清除上一次运行留下的取消位。
        cancelled.set(false);
        EventSink sink = event -> store.append(sessionId, event);
        sink.emit(new SessionEvent.MessageAdded(SessionEvent.Role.USER, input));
        StrategyHandler<RunRequest, RunContext, StepResult> root =
                RunChainFactory.assemble(
                        store, model, toolRegistry, approvalPolicy, approvalGate);
        Phase.Running phase = Phase.idle().start();
        boolean aborted = false;

        try {
            // 步数从 0 开始计数，取消和上限都在执行下一步前检查。
            while (phase.step() < maxSteps && !cancelled.get()) {
                RunContext context = new RunContext(sessionId, sink, streamSink);
                StepResult result = root.apply(
                        new RunRequest(sessionId, input, phase.step()), context);
                // 取消可能发生在单步内部，需在推进前优先识别并记录中止。
                if (cancelled.get() || result.aborted()) {
                    aborted = true;
                    break;
                }
                if (result.finished()) {
                    break;
                }
                phase = phase.nextStep();
            }
            // 自然结束后再检查取消与上限，覆盖循环退出时刚好触发的边界条件。
            aborted = aborted || cancelled.get() || phase.step() >= maxSteps;
        } catch (Exception exception) {
            // 统一把异常收敛为中止，保证每次 run 都有终态和 RunFinished 事实。
            log.error("run failed: session={}", sessionId, exception);
            aborted = true;
        }
        finalPhase = aborted ? phase.abort() : phase.complete();
        sink.emit(new SessionEvent.RunFinished(aborted));
    }

    /** 请求取消当前运行；该请求会在运行循环的下一次边界检查时生效。 */
    public void cancel() {
        cancelled.set(true);
    }

    /**
     * 返回最近一次运行结束后的阶段。
     *
     * @return 已完成或已中止的阶段
     */
    public Phase finalPhase() {
        return finalPhase;
    }
}
