package cn.yhjyhj.weaver.core.loop.node;

import cn.yhjyhj.weaver.core.loop.chain.AbstractRunNode;
import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.loop.context.RunRequest;
import cn.yhjyhj.weaver.core.loop.context.StepResult;
import cn.yhjyhj.weaver.core.model.StreamChunk;

/** 观察本步模型与工具结果，并决定运行是否继续。 */
public final class ObserveNode extends AbstractRunNode {

    /** 创建策略链末端的结果收敛节点。 */
    public ObserveNode() {
        super((request, context) -> null);
    }

    /**
     * 根据本步模型与工具状态决定整个运行完成、中止还是继续下一步。
     *
     * <p>输入是 {@link RunContext} 中的模型失败标记、模型完成标记、停止原因、
     * 工具执行标记和已累积文本。本节点不修改上下文，也不 emit 事件，只返回
     * {@link StepResult} 供 RunLoop 推进状态。
     *
     * <p>关键分支：模型失败时返回 aborted；因长度限制停止时暂时按自然完成处理；
     * 模型完成且本步没有执行工具时自然完成；其余情况返回未完成，让下一 step 重新回放
     * 工具结果并继续推理。这里处理的都是已经收敛到上下文中的失败状态，不会主动抛异常。
     */
    @Override
    protected StepResult doApply(RunRequest request, RunContext context) {
        if (context.isModelFailed()) {
            return new StepResult(false, true, context.getResponseText());
        }
        if (context.isModelDone() && context.getStopReason() == StreamChunk.StopReason.LENGTH) {
            // TODO(M2): 按 StopReason.LENGTH 做最多 4 次受控续写，而不是直接结束
            return new StepResult(true, false, context.getResponseText());
        }
        if (context.isModelDone() && !context.isToolExecuted()) {
            return new StepResult(true, false, context.getResponseText());
        }
        // 工具已执行后需要进入下一步，让 Context 回放 TOOL 消息并再次推理。
        return new StepResult(false, false, context.getResponseText());
    }
}
