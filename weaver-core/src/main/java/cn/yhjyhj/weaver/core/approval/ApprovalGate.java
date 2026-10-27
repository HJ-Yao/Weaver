package cn.yhjyhj.weaver.core.approval;

import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Reversibility;

/** ASK 决策时向用户请求批准；实现方负责阻塞直到答复。 */
@FunctionalInterface
public interface ApprovalGate {

    /**
     * 请求用户批准一次工具调用。
     *
     * @param call 待批准的工具调用
     * @param reversibility 工具声明的回滚性
     * @return 用户是否批准
     */
    boolean requestApproval(StreamChunk.ToolCall call, Reversibility reversibility);

    /** 无 UI 时的安全默认：一律拒绝。 */
    static ApprovalGate denyAll() {
        return (call, reversibility) -> false;
    }
}
