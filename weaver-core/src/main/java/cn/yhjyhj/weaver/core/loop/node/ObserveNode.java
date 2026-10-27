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
