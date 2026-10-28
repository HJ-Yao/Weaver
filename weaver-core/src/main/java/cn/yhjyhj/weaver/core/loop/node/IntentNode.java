package cn.yhjyhj.weaver.core.loop.node;

import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.loop.chain.AbstractRunNode;
import cn.yhjyhj.weaver.core.loop.chain.StrategyHandler;
import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.loop.context.RunRequest;
import cn.yhjyhj.weaver.core.loop.context.StepResult;
import cn.yhjyhj.weaver.core.loop.intent.Intent;
import cn.yhjyhj.weaver.core.loop.intent.IntentClassifier;
import cn.yhjyhj.weaver.core.loop.intent.RuleIntentClassifier;

/** 在本轮首步识别用户意图，并在信息不足时直接追问。 */
public final class IntentNode extends AbstractRunNode {

    /** 信息不足时返回给调用方的追问文本。 */
    private static final String CLARIFY_TEXT = "请补充更多信息。";

    private final IntentClassifier classifier; // 意图分类策略
    private final StrategyHandler<RunRequest, RunContext, StepResult> reasonNode; // 可继续推理的节点

    /**
     * 创建使用 M1 规则分类器的意图节点。
     *
     * @param reasonNode 意图明确后继续执行的推理节点
     */
    public IntentNode(StrategyHandler<RunRequest, RunContext, StepResult> reasonNode) {
        this(new RuleIntentClassifier(), reasonNode);
    }

    /**
     * 创建意图识别节点。
     *
     * @param classifier 意图分类策略
     * @param reasonNode 意图明确后继续执行的推理节点
     */
    public IntentNode(
            IntentClassifier classifier,
            StrategyHandler<RunRequest, RunContext, StepResult> reasonNode) {
        super(reasonNode);
        this.classifier = classifier;
        this.reasonNode = reasonNode;
    }

    /**
     * 在首步识别用户意图；信息不足时直接结束本轮并请求补充信息。
     *
     * <p>输入是 {@link RunRequest#step()} 与 {@link RunRequest#input()}，分类结果写入
     * {@link RunContext#setIntent(Intent)}。step 大于 0 时本节点不重复分类，也不修改上下文。
     *
     * <p>关键分支：明确意图时返回 {@code null}，继续进入推理；分类结果为
     * {@link Intent#CLARIFY} 时 emit 一条 assistant 追问消息，并返回已完成的结果以短路
     * 后续模型调用。分类器抛出的异常不被吞掉，由 RunLoop 收敛为运行中止。
     */
    @Override
    protected StepResult doApply(RunRequest request, RunContext context) {
        if (request.step() > 0) {
            return null;
        }

        Intent intent = classifier.classify(request.input());
        context.setIntent(intent);
        if (intent == Intent.CLARIFY) {
            context.getSink().emit(new SessionEvent.MessageAdded(
                    SessionEvent.Role.ASSISTANT, CLARIFY_TEXT));
            return new StepResult(true, false, CLARIFY_TEXT);
        }
        return null;
    }

    /**
     * 意图明确时进入模型推理。
     *
     * @param request 当前步骤请求
     * @param context 当前步骤上下文
     * @return 推理节点
     */
    @Override
    public StrategyHandler<RunRequest, RunContext, StepResult> getNext(
            RunRequest request, RunContext context) {
        return reasonNode;
    }
}
