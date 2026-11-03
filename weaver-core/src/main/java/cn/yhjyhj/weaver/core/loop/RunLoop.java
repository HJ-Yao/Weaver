package cn.yhjyhj.weaver.core.loop;

import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.SessionEventStore;
import cn.yhjyhj.weaver.core.loop.chain.StrategyHandler;
import cn.yhjyhj.weaver.core.loop.context.EventSink;
import cn.yhjyhj.weaver.core.loop.context.Inbox;
import cn.yhjyhj.weaver.core.loop.context.RunCancellation;
import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.loop.context.RunRequest;
import cn.yhjyhj.weaver.core.loop.context.StepResult;
import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.phase.Phase;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * 直线驱动单步策略链的运行循环。
 *
 * <p>一次运行会处理初始输入及运行期间通过 {@link #enqueue(String)} 排队的输入，
 * {@code maxSteps} 按轮独立计数。线程契约：一个实例只调用一次
 * {@link #run(String, String)}；多次运行或并发会话请各自创建实例。
 * {@link #cancel()} 与 {@link #enqueue(String)} 可以从其他线程调用。
 */
@Slf4j
public final class RunLoop {

    /** 单轮默认最大步数；达到上限按中止处理。 */
    public static final int DEFAULT_MAX_STEPS = RunLoopConfig.DEFAULT_MAX_STEPS;

    private final ModelClient model; // 模型调用边界
    private final SessionEventStore store; // 会话事实源
    private final RunLoopConfig config; // 本次运行的步数、流式出口与协作者
    /** 当前运行实例的取消信号；实例只服务一次 run。 */
    private final RunCancellation cancellation = new RunCancellation();
    /** 运行期间等待后续轮次处理的用户输入。 */
    private final Inbox inbox = new Inbox();
    /** 当前是否处于一次 run 的生命周期内，供跨线程 enqueue 判定。 */
    private volatile boolean running;
    /** 最近一次运行收敛后的阶段；run 返回后由调用线程读取。 */
    private volatile Phase finalPhase;

    /**
     * 使用默认配置创建运行循环。
     *
     * @param model 模型调用边界
     * @param store 会话事件存储
     */
    public RunLoop(ModelClient model, SessionEventStore store) {
        this(model, store, RunLoopConfig.defaults());
    }

    /**
     * 使用显式配置创建运行循环。
     *
     * @param model 模型调用边界
     * @param store 会话事件存储
     * @param config 单轮步数、流式出口与协作者配置
     */
    public RunLoop(ModelClient model, SessionEventStore store, RunLoopConfig config) {
        this.model = model;
        this.store = store;
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * 执行一次会话运行，直到所有排队轮次完成、任一轮达到步数上限、失败或收到取消请求。
     *
     * <p>输入是首轮用户文本 {@code input}、会话标识 {@code sessionId}，以及构造时装配的
     * 模型、事件存储、工具注册表和审批依赖。每轮先持久化该轮用户消息，再从 step 0 开始
     * 逐步执行策略链；每一步使用新的 {@link RunContext}，跨步事实只从事件存储回放。
     * 一轮自然结束后，若运行期间有排队输入则进入下一轮并重置步数预算。
     *
     * <p>产出是完整会话事件流：节点追加的 assistant/tool/approval 事件，以及最终一条
     * {@link SessionEvent.RunFinished}。运行结束后 {@link #finalPhase()} 暴露完成态或
     * 中止态，供调用方审计。
     *
     * <p>关键分支：单步结果声明 aborted、运行中收到取消，或任一轮执行完最后允许步数时
     * 整个运行转为中止，剩余排队输入不再处理；单步结果声明 finished 时结束当前轮，否则
     * 推进下一步。失败处理：策略链或模型流基础设施抛出的异常会被记录并收敛为 aborted，
     * 不向调用线程继续抛出，且始终写出终止事件。
     *
     * @param sessionId 当前会话标识
     * @param input 本轮用户输入
     */
    public void run(String sessionId, String input) {
        EventSink sink = event -> store.append(sessionId, event);
        StrategyHandler<RunRequest, RunContext, StepResult> root =
                RunChainFactory.assemble(
                        store,
                        model,
                        config.toolRegistry(),
                        config.approvalPolicy(),
                        config.approvalGate(),
                        config.approvalGrants());
        Phase.Running phase = Phase.idle().start();
        boolean aborted = false;
        String current = input;

        running = true;
        try {
            // 每轮开始记录用户事实；轮间只从信箱取一条，保持输入到达顺序。
            while (current != null && !cancellation.isCancelled()) {
                sink.emit(new SessionEvent.MessageAdded(SessionEvent.Role.USER, current));
                TurnOutcome turn = runTurn(sessionId, current, phase, root, sink);
                phase = turn.phase();
                if (turn.aborted()) {
                    aborted = true;
                    break;
                }
                current = inbox.poll();
                if (current != null) {
                    phase = phase.nextTurn();
                }
            }
            aborted = aborted || cancellation.isCancelled();
        } catch (Exception exception) {
            // 统一把异常收敛为中止，保证每次 run 都有终态和 RunFinished 事实。
            log.error("run failed: session={}", sessionId, exception);
            aborted = true;
        } finally {
            running = false;
        }
        finalPhase = aborted ? phase.abort() : phase.complete();
        sink.emit(new SessionEvent.RunFinished(aborted));
    }

    /**
     * 运行期间投递一条用户输入；未运行或输入空白时返回 false。
     * 输入在当前轮结束后才会被处理。
     *
     * <p>边界：恰好在最后一轮结束、run 返回之前入队的输入会返回 true 但不会被处理。
     * 由传输层（M2.2）用「运行中才入队、否则新建运行」的语义兜底。
     *
     * @param input 待处理用户输入
     * @return 输入是否成功进入当前运行的待处理队列
     */
    public boolean enqueue(String input) {
        Objects.requireNonNull(input, "input");
        if (!running || input.isBlank()) {
            return false;
        }
        inbox.offer(input);
        return true;
    }

    /** 请求取消当前运行；立即触发已注册回调，并保证循环在下一步判定中止。 */
    public void cancel() {
        cancellation.cancel();
    }

    /**
     * 返回最近一次运行结束后的阶段。
     *
     * @return 已完成或已中止的阶段
     */
    public Phase finalPhase() {
        return finalPhase;
    }

    /**
     * 执行当前轮，直到自然完成、达到本轮步数上限或收到取消。
     *
     * <p>每次调用都从传入 phase 的当前 step 继续；正常结束后由外层循环决定是否进入
     * 下一轮。取消或单步声明 aborted 时立即结束整次运行；本轮最后一步仍未完成时也按
     * 中止处理。
     *
     * @param sessionId 当前会话标识
     * @param input 本轮用户输入
     * @param phase 本轮起始进行态
     * @param root 单步策略链入口
     * @param sink 会话事件出口
     * @return 本轮结束阶段及是否中止整次运行
     */
    private TurnOutcome runTurn(
            String sessionId,
            String input,
            Phase.Running phase,
            StrategyHandler<RunRequest, RunContext, StepResult> root,
            EventSink sink) throws Exception {
        // 步数只在本轮内递增；首步和工具回灌后的步骤共享同一轮预算。
        while (phase.step() < config.maxSteps() && !cancellation.isCancelled()) {
            RunContext context =
                    new RunContext(sessionId, sink, config.streamSink(), cancellation);
            StepResult result = root.apply(
                    new RunRequest(sessionId, input, phase.step()), context);
            // 取消可能发生在单步内部，需在推进前优先识别并记录中止。
            if (cancellation.isCancelled() || result.aborted()) {
                return new TurnOutcome(phase, true);
            }
            if (result.finished()) {
                return new TurnOutcome(phase, false);
            }
            phase = phase.nextStep();
        }
        return new TurnOutcome(
                phase,
                cancellation.isCancelled() || phase.step() >= config.maxSteps());
    }

    /**
     * 单轮执行结果。
     *
     * @param phase 本轮结束时的运行态
     * @param aborted 是否应中止整个运行
     */
    private record TurnOutcome(Phase.Running phase, boolean aborted) {}
}
