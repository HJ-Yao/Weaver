package cn.yhjyhj.weaver.core.loop;

import cn.yhjyhj.weaver.core.approval.ApprovalGate;
import cn.yhjyhj.weaver.core.approval.ApprovalGrantStore;
import cn.yhjyhj.weaver.core.approval.ApprovalPolicy;
import cn.yhjyhj.weaver.core.approval.RuleBasedApprovalPolicy;
import cn.yhjyhj.weaver.core.events.SessionEventStore;
import cn.yhjyhj.weaver.core.loop.chain.StrategyHandler;
import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.loop.context.RunRequest;
import cn.yhjyhj.weaver.core.loop.context.StepResult;
import cn.yhjyhj.weaver.core.loop.node.ActNode;
import cn.yhjyhj.weaver.core.loop.node.ContextNode;
import cn.yhjyhj.weaver.core.loop.node.IntentNode;
import cn.yhjyhj.weaver.core.loop.node.ObserveNode;
import cn.yhjyhj.weaver.core.loop.node.ReasonNode;
import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;

/** 在运行时装配 Context、Intent、Reason、Act、Observe 五节点链。 */
public final class RunChainFactory {

    private RunChainFactory() {}

    /**
     * 装配单步 ReAct 策略链。
     *
     * <p>首步由 Context 进入 Intent；工具回灌后的步骤由 Context 直接进入 Reason。
     *
     * @param store 会话事件存储
     * @param model 模型调用边界
     * @return 策略链入口
     */
    public static StrategyHandler<RunRequest, RunContext, StepResult> assemble(
            SessionEventStore store, ModelClient model) {
        return assemble(store, model, ToolRegistry.empty());
    }

    /**
     * 使用给定工具注册表装配单步 ReAct 策略链。
     *
     * @param store 会话事件存储
     * @param model 模型调用边界
     * @param registry 工具注册表
     * @return 策略链入口
     */
    public static StrategyHandler<RunRequest, RunContext, StepResult> assemble(
            SessionEventStore store, ModelClient model, ToolRegistry registry) {
        return assemble(
                store,
                model,
                registry,
                RuleBasedApprovalPolicy.defaults(),
                ApprovalGate.denyAll(),
                ApprovalGrantStore.none());
    }

    /**
     * 使用显式审批依赖装配单步 ReAct 策略链。
     *
     * @param store 会话事件存储
     * @param model 模型调用边界
     * @param registry 工具注册表
     * @param approvalPolicy 工具调用审批策略
     * @param approvalGate ASK 时等待用户答复的闸门
     * @param grants 会话授权记录
     * @return 策略链入口
     */
    public static StrategyHandler<RunRequest, RunContext, StepResult> assemble(
            SessionEventStore store,
            ModelClient model,
            ToolRegistry registry,
            ApprovalPolicy approvalPolicy,
            ApprovalGate approvalGate,
            ApprovalGrantStore grants) {
        ObserveNode observe = new ObserveNode();
        ActNode act = new ActNode(
                registry, observe, approvalPolicy, approvalGate, grants);
        ReasonNode reason = new ReasonNode(model, registry, act, observe);
        IntentNode intent = new IntentNode(reason);
        return new ContextNode(store, intent, reason);
    }
}
