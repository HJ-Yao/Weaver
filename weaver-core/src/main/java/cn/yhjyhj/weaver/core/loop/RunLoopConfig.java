package cn.yhjyhj.weaver.core.loop;

import cn.yhjyhj.weaver.core.approval.ApprovalGate;
import cn.yhjyhj.weaver.core.approval.ApprovalGrantStore;
import cn.yhjyhj.weaver.core.approval.ApprovalPolicy;
import cn.yhjyhj.weaver.core.approval.RuleBasedApprovalPolicy;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.model.StreamSink;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import java.util.Objects;

/**
 * RunLoop 的可选协作者与运行参数。
 *
 * @param maxSteps 单轮最大步数
 * @param streamSink 流式输出旁路
 * @param toolRegistry 工具注册表
 * @param approvalPolicy 审批策略
 * @param approvalGate 审批闸门
 * @param approvalGrants 会话授权记录
 */
public record RunLoopConfig(
        int maxSteps,
        StreamSink streamSink,
        ToolRegistry toolRegistry,
        ApprovalPolicy approvalPolicy,
        ApprovalGate approvalGate,
        ApprovalGrantStore approvalGrants) {

    /** 单轮默认最大步数。 */
    public static final int DEFAULT_MAX_STEPS = 50;

    /** 未配置流式出口时丢弃所有回调的实现。 */
    private static final StreamSink NO_OP_STREAM_SINK = new StreamSink() {
        @Override
        public void onDelta(String delta) {}

        @Override
        public void onToolCall(StreamChunk.ToolCall call) {}

        @Override
        public void onDone(StreamChunk.StopReason reason) {}
    };

    public RunLoopConfig {
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        streamSink = Objects.requireNonNull(streamSink, "streamSink");
        toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        approvalGate = Objects.requireNonNull(approvalGate, "approvalGate");
        approvalGrants = Objects.requireNonNull(approvalGrants, "approvalGrants");
    }

    /** 默认配置：50 步、无流式出口、空工具、默认审批、拒绝闸门、无授权。 */
    public static RunLoopConfig defaults() {
        return new RunLoopConfig(
                DEFAULT_MAX_STEPS,
                NO_OP_STREAM_SINK,
                ToolRegistry.empty(),
                RuleBasedApprovalPolicy.defaults(),
                ApprovalGate.denyAll(),
                ApprovalGrantStore.none());
    }
}
