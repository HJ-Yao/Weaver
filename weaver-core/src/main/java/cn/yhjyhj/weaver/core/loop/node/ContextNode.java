package cn.yhjyhj.weaver.core.loop.node;

import cn.yhjyhj.weaver.core.events.SessionEventStore;
import cn.yhjyhj.weaver.core.events.SessionEvents;
import cn.yhjyhj.weaver.core.loop.chain.AbstractRunNode;
import cn.yhjyhj.weaver.core.loop.chain.StrategyHandler;
import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.loop.context.RunRequest;
import cn.yhjyhj.weaver.core.loop.context.StepResult;

/** 从事件流重建本步所需的历史快照，并按步骤选择意图或推理节点。 */
public final class ContextNode extends AbstractRunNode {

    private final SessionEventStore store; // 会话事实源
    /** 首步识别用户意图的节点。 */
    private final StrategyHandler<RunRequest, RunContext, StepResult> intentNode;
    /** 工具回灌后继续推理的节点。 */
    private final StrategyHandler<RunRequest, RunContext, StepResult> reasonNode;

    /**
     * 创建上下文节点。
     *
     * @param store 会话事件存储
     * @param intentNode 首步的意图识别节点
     * @param reasonNode 后续步骤的推理节点
     */
    public ContextNode(
            SessionEventStore store,
            StrategyHandler<RunRequest, RunContext, StepResult> intentNode,
            StrategyHandler<RunRequest, RunContext, StepResult> reasonNode) {
        super(reasonNode);
        this.store = store;
        this.intentNode = intentNode;
        this.reasonNode = reasonNode;
    }

    /**
     * 从事件流重建当前步骤所需的历史快照，并把路由权交给首步或后续推理节点。
     *
     * <p>输入是 {@link RunRequest#sessionId()} 指示的会话事件。无论第几步，本方法都从
     * seq 0 回放事实，把结果写入 {@link RunContext#getHistory()}；本方法自身不 emit 事件，
     * 也不生成 {@link StepResult}。
     *
     * <p>关键分支由 {@link #getNext(RunRequest, RunContext)} 决定：step 0 进入意图识别，
     * 后续 step 直接进入推理，使工具执行结果能在下一步被模型看到。读取或回放失败时异常
     * 原样抛出，由 RunLoop 统一记录并收敛为运行中止。
     */
    @Override
    protected StepResult doApply(RunRequest request, RunContext context) {
        // TODO(M3): 投影缓存按 lastSeq 失效，避免每步全量 replay
        context.setHistory(SessionEvents.replay(store.from(request.sessionId(), 0)));
        return null;
    }

    /**
     * 首步识别意图，后续步骤直接把含工具结果的历史送入推理。
     *
     * @param request 当前步骤请求
     * @param context 当前步骤上下文
     * @return 首步为意图节点，后续为推理节点
     */
    @Override
    public StrategyHandler<RunRequest, RunContext, StepResult> getNext(
            RunRequest request, RunContext context) {
        return request.step() == 0 ? intentNode : reasonNode;
    }
}
