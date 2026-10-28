package cn.yhjyhj.weaver.core.loop.chain;

import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.loop.context.RunRequest;
import cn.yhjyhj.weaver.core.loop.context.StepResult;

/**
 * 固定单步泛型绑定的策略节点基类。
 */
public abstract class AbstractRunNode
        extends AbstractStrategyRouter<RunRequest, RunContext, StepResult> {

    /** 当前节点返回 null 后要继续执行的固定后继。 */
    protected final StrategyHandler<RunRequest, RunContext, StepResult> next;

    /**
     * 创建带固定后继的节点。
     *
     * @param next 当前节点无产出时执行的下一节点
     */
    protected AbstractRunNode(StrategyHandler<RunRequest, RunContext, StepResult> next) {
        this.next = next;
    }

    /**
     * 返回装配时指定的固定后继节点。
     *
     * @param request 当前步骤请求
     * @param context 当前步骤上下文
     * @return 固定后继节点
     */
    @Override
    public StrategyHandler<RunRequest, RunContext, StepResult> getNext(
            RunRequest request, RunContext context) {
        return next;
    }
}
