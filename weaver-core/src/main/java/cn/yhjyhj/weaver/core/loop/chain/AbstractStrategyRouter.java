package cn.yhjyhj.weaver.core.loop.chain;

/**
 * 责任链模板：节点有产出时短路，无产出时按上下文选择下一节点。
 *
 * @param <I> 请求类型
 * @param <C> 可变上下文类型
 * @param <O> 结果类型
 */
public abstract class AbstractStrategyRouter<I, C, O> implements StrategyHandler<I, C, O> {

    /** 默认后继处理器；具体路由实现可据此提供兜底节点。 */
    protected StrategyHandler<I, C, O> defaultStrategyHandler = (request, context) -> null;

    protected abstract O doApply(I request, C context) throws Exception;

    /**
     * 选择当前请求的后继处理器。
     *
     * @param request 当前步骤请求
     * @param context 当前步骤上下文
     * @return 下一节点
     * @throws Exception 路由计算失败时抛出
     */
    public abstract StrategyHandler<I, C, O> getNext(I request, C context) throws Exception;

    /**
     * 执行当前节点，并在节点无产出时继续责任链。
     *
     * @param request 当前步骤请求
     * @param context 当前步骤上下文
     * @return 当前节点或后继节点的结果
     * @throws Exception 节点或路由执行失败时抛出
     */
    @Override
    public final O apply(I request, C context) throws Exception {
        O result = doApply(request, context);
        // 非 null 表示当前节点已经接管本步，必须短路以避免后继重复产生副作用。
        if (result != null) {
            return result;
        }
        // null 表示当前节点不产出结果，本步继续交由路由选出的后继节点处理。
        return router(request, context);
    }

    protected O router(I request, C context) throws Exception {
        return getNext(request, context).apply(request, context);
    }
}
