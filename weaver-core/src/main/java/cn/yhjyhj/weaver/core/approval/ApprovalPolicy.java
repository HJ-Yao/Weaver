package cn.yhjyhj.weaver.core.approval;

import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Reversibility;

/** 根据工具调用、回滚性和运行期上下文计算审批结论。 */
@FunctionalInterface
public interface ApprovalPolicy {

    /**
     * 计算一次工具调用的审批结论。
     *
     * @param call 待审批的工具调用
     * @param reversibility 工具声明的回滚性
     * @param context 会话交互能力与授权记录
     * @return 最终结论及可审计原因
     */
    ApprovalResult decide(
            StreamChunk.ToolCall call,
            Reversibility reversibility,
            ApprovalContext context);
}
