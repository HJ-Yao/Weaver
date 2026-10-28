package cn.yhjyhj.weaver.core.approval;

import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Reversibility;

/** 根据工具调用及其回滚性计算审批结论。 */
@FunctionalInterface
public interface ApprovalPolicy {

    /**
     * 计算一次工具调用的审批结论。
     *
     * @param call 待审批的工具调用
     * @param reversibility 工具声明的回滚性
     * @return 是否允许、询问或拒绝
     */
    ApprovalDecision decide(StreamChunk.ToolCall call, Reversibility reversibility);
}
