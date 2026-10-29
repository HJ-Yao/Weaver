package cn.yhjyhj.weaver.core.approval;

import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Reversibility;

/** ASK 决策时向用户请求批准；实现方负责阻塞直到答复。 */
@FunctionalInterface
public interface ApprovalGate {

    /**
     * 是否存在可询问用户的界面。
     *
     * @return 默认存在交互界面
     */
    default boolean isInteractive() {
        return true;
    }

    /**
     * 请求用户批准一次工具调用。
     *
     * @param call 待批准的工具调用
     * @param reversibility 工具声明的回滚性
     * @return 用户是否批准
     */
    boolean requestApproval(StreamChunk.ToolCall call, Reversibility reversibility);

    /**
     * 创建无人值守的安全闸门。
     *
     * @return 显式声明无 UI 且一律拒绝的闸门
     */
    static ApprovalGate denyAll() {
        return new ApprovalGate() {
            @Override
            public boolean isInteractive() {
                return false;
            }

            @Override
            public boolean requestApproval(
                    StreamChunk.ToolCall call, Reversibility reversibility) {
                return false;
            }
        };
    }
}
