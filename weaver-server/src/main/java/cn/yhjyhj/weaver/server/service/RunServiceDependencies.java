package cn.yhjyhj.weaver.server.service;

import cn.yhjyhj.weaver.core.approval.ApprovalGrantStore;
import cn.yhjyhj.weaver.core.approval.ApprovalPolicy;
import cn.yhjyhj.weaver.core.events.SessionEventStore;
import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RunService 的进程级协作者与单次运行参数。
 *
 * @param sessions 会话元数据服务
 * @param eventStore 会话事件事实源
 * @param model 模型客户端
 * @param tools 工具注册表
 * @param approvalPolicy 工具审批策略
 * @param approvalGrants 会话审批授权记录
 * @param maxSteps 单轮运行最大步数
 * @param approvalTimeout 单次交互审批等待上限
 * @param emitterFactory 单次请求的 SSE 发射器工厂
 */
public record RunServiceDependencies(
        SessionService sessions,
        SessionEventStore eventStore,
        ModelClient model,
        ToolRegistry tools,
        ApprovalPolicy approvalPolicy,
        ApprovalGrantStore approvalGrants,
        int maxSteps,
        Duration approvalTimeout,
        Supplier<SseEmitter> emitterFactory) {

    public RunServiceDependencies {
        sessions = Objects.requireNonNull(sessions, "sessions");
        eventStore = Objects.requireNonNull(eventStore, "eventStore");
        model = Objects.requireNonNull(model, "model");
        tools = Objects.requireNonNull(tools, "tools");
        approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        approvalGrants = Objects.requireNonNull(approvalGrants, "approvalGrants");
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        approvalTimeout = Objects.requireNonNull(approvalTimeout, "approvalTimeout");
        if (approvalTimeout.isNegative()) {
            throw new IllegalArgumentException("approvalTimeout must not be negative");
        }
        emitterFactory = Objects.requireNonNull(emitterFactory, "emitterFactory");
    }

    /** 生产默认的 SSE 发射器工厂。 */
    public static Supplier<SseEmitter> defaultEmitterFactory() {
        return () -> new SseEmitter(0L);
    }
}
