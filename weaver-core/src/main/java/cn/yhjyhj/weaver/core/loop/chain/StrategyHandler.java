package cn.yhjyhj.weaver.core.loop.chain;

/**
 * 单步编排中的一个策略节点。
 *
 * @param <I> 请求类型
 * @param <C> 单步上下文类型
 * @param <O> 节点结果类型
 */
@FunctionalInterface
public interface StrategyHandler<I, C, O> {

    /**
     * 处理当前请求；实现可以执行副作用或把请求交给后继节点。
     *
     * @param request 当前步骤请求
     * @param context 当前步骤上下文
     * @return 节点结果；null 的具体续链语义由路由器决定
     * @throws Exception 节点执行失败时抛出
     */
    O apply(I request, C context) throws Exception;
}
