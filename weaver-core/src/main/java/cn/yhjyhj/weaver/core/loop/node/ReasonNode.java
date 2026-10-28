package cn.yhjyhj.weaver.core.loop.node;

import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.loop.chain.AbstractRunNode;
import cn.yhjyhj.weaver.core.loop.chain.StrategyHandler;
import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.loop.context.RunRequest;
import cn.yhjyhj.weaver.core.loop.context.StepResult;
import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.model.ModelRequest;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/** 调用模型、实时转发文本分片，并收集本步全部工具调用。 */
public final class ReasonNode extends AbstractRunNode {

    private final ModelClient model; // 模型调用边界
    private final ToolRegistry registry; // 提供给模型的可用工具描述来源
    /** 检测到工具调用时进入的执行节点。 */
    private final StrategyHandler<RunRequest, RunContext, StepResult> actNode;
    /** 没有工具调用时进入的结果收敛节点。 */
    private final StrategyHandler<RunRequest, RunContext, StepResult> observeNode;

    /**
     * 创建推理节点。
     *
     * @param model 模型调用边界
     * @param registry 可用工具注册表
     * @param actNode 有待执行工具调用时的后继节点
     * @param observeNode 无工具调用时的后继节点
     */
    public ReasonNode(
            ModelClient model,
            ToolRegistry registry,
            StrategyHandler<RunRequest, RunContext, StepResult> actNode,
            StrategyHandler<RunRequest, RunContext, StepResult> observeNode) {
        super(observeNode);
        this.model = model;
        this.registry = registry;
        this.actNode = actNode;
        this.observeNode = observeNode;
    }

    /**
     * 调用模型生成当前步骤的回复，并收集文本、结束原因和工具调用。
     *
     * <p>输入是 {@link RunRequest#input()}、{@link RunContext#getHistory()} 的消息，
     * 以及注册表声明的工具清单。模型流会被同步订阅：文本同时写入上下文和实时输出端，
     * 工具调用进入待执行列表；非空最终文本作为一条 assistant 消息写入事件流。
     *
     * <p>本方法始终返回 {@code null}，由 {@link #getNext(RunRequest, RunContext)}
     * 根据是否收集到工具调用决定进入 ActNode 或 ObserveNode。模型流中的错误会标记
     * 上下文失败并正常结束等待；调用模型或订阅基础设施抛出的异常则交由 RunLoop
     * 统一收敛为运行中止。
     */
    @Override
    protected StepResult doApply(RunRequest request, RunContext context) {
        StringBuilder text = new StringBuilder();
        CompletableFuture<Void> completion = new CompletableFuture<>();
        ModelRequest modelRequest = new ModelRequest(
                request.input(), context.getHistory().messages(), registry.specs());
        model.stream(modelRequest).subscribe(new ChunkSubscriber(context, text, completion));
        // TODO(M2): 改为 inbox 驱动的异步推进，避免阻塞调用线程
        completion.join();
        context.setResponseText(text.toString());
        // 实时分片不落事件流；纯工具调用轮次没有文本，不应写入空助手消息。
        if (!text.isEmpty()) {
            context.getSink().emit(new SessionEvent.MessageAdded(
                    SessionEvent.Role.ASSISTANT, text.toString()));
        }
        return null;
    }

    /**
     * 根据是否收集到工具调用选择执行或结果收敛节点。
     *
     * @param request 当前步骤请求
     * @param context 当前步骤上下文
     * @return 工具执行节点或结果收敛节点
     */
    @Override
    public StrategyHandler<RunRequest, RunContext, StepResult> getNext(
            RunRequest request, RunContext context) {
        return context.getPendingToolCalls().isEmpty() ? observeNode : actNode;
    }

    /** 将模型分片同步写入步骤状态和实时输出出口。 */
    private static final class ChunkSubscriber implements Flow.Subscriber<StreamChunk> {

        private final RunContext context; // 分片状态的目标上下文
        private final StringBuilder text; // 本步累积文本
        private final CompletableFuture<Void> completion; // 流终止信号
        private Flow.Subscription subscription; // 当前模型流订阅

        private ChunkSubscriber(
                RunContext context,
                StringBuilder text,
                CompletableFuture<Void> completion) {
            this.context = context;
            this.text = text;
            this.completion = completion;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(StreamChunk chunk) {
            if (chunk instanceof StreamChunk.Text delta) {
                text.append(delta.delta());
                context.getStreamSink().onDelta(delta.delta());
            } else if (chunk instanceof StreamChunk.ToolCall toolCall) {
                context.getStreamSink().onToolCall(toolCall);
                context.getPendingToolCalls().add(toolCall);
            } else if (chunk instanceof StreamChunk.Done done) {
                context.setModelDone(true);
                context.setStopReason(done.reason());
                context.getStreamSink().onDone(done.reason());
            } else if (chunk instanceof StreamChunk.Error error) {
                context.setModelFailed(true);
                context.getStreamSink().onError(error.message());
                // 错误后立即取消订阅，并跳过后续 request，避免继续消费残余分片。
                subscription.cancel();
                completion.complete(null);
                return;
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            context.setModelFailed(true);
            context.getStreamSink().onError(throwable.getMessage());
            completion.complete(null);
        }

        @Override
        public void onComplete() {
            completion.complete(null);
        }
    }
}
