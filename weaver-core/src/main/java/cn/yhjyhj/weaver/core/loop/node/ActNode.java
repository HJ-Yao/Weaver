package cn.yhjyhj.weaver.core.loop.node;

import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.loop.chain.AbstractRunNode;
import cn.yhjyhj.weaver.core.loop.chain.StrategyHandler;
import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.loop.context.RunRequest;
import cn.yhjyhj.weaver.core.loop.context.StepResult;
import cn.yhjyhj.weaver.core.model.StreamChunk;

/** 执行本步收集到的工具调用，并为每次调用记录开始与完成事实。 */
public final class ActNode extends AbstractRunNode {

    /**
     * 创建工具执行节点。
     *
     * @param observeNode 工具执行完成后进入的结果收敛节点
     */
    public ActNode(StrategyHandler<RunRequest, RunContext, StepResult> observeNode) {
        super(observeNode);
    }

    @Override
    protected StepResult doApply(RunRequest request, RunContext context) {
        for (StreamChunk.ToolCall toolCall : context.getPendingToolCalls()) {
            // TODO(M1-T4): 接入 Tool SPI 与真实工具执行
            context.getSink().emit(new SessionEvent.ToolCallStarted(
                    toolCall.callId(), toolCall.toolName(), toolCall.argumentsJson()));
            context.getSink().emit(new SessionEvent.ToolCallCompleted(
                    toolCall.callId(), new ToolResult("ok", false, 0L)));
        }
        context.getPendingToolCalls().clear();
        context.setToolExecuted(true);
        return null;
    }
}
