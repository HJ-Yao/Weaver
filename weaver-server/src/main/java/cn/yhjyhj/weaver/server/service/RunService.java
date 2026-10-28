package cn.yhjyhj.weaver.server.service;

import cn.yhjyhj.weaver.core.approval.ApprovalGate;
import cn.yhjyhj.weaver.core.approval.ApprovalPolicy;
import cn.yhjyhj.weaver.core.events.SessionEventStore;
import cn.yhjyhj.weaver.core.loop.RunLoop;
import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import cn.yhjyhj.weaver.server.config.WeaverProperties;
import cn.yhjyhj.weaver.server.sse.SseStreamSink;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 为每次消息请求装配独立 RunLoop，并把实时输出桥接到 SSE。 */
@Slf4j
@Service
public class RunService {

    private final SessionService sessions; // 会话存在性边界
    private final SessionEventStore eventStore; // 运行事实源
    private final ModelClient model; // 当前模型客户端
    private final ToolRegistry tools; // 进程级工具注册表
    private final ApprovalPolicy approvalPolicy; // 工具审批策略
    private final ApprovalGate approvalGate; // 工具审批闸门
    private final int maxSteps; // 单轮运行的最大步数
    private final Supplier<SseEmitter> emitterFactory; // 单次请求的响应发射器工厂

    /**
     * 使用应用配置创建运行服务。
     *
     * @param sessions 会话元数据服务
     * @param eventStore 会话事件事实源
     * @param model 模型客户端
     * @param tools 工具注册表
     * @param approvalPolicy 工具审批策略
     * @param approvalGate 工具审批闸门
     * @param properties Weaver 运行配置
     */
    @Autowired
    public RunService(
            SessionService sessions,
            SessionEventStore eventStore,
            ModelClient model,
            ToolRegistry tools,
            ApprovalPolicy approvalPolicy,
            ApprovalGate approvalGate,
            WeaverProperties properties) {
        this(
                sessions,
                eventStore,
                model,
                tools,
                approvalPolicy,
                approvalGate,
                properties.maxSteps(),
                () -> new SseEmitter(0L));
    }

    RunService(
            SessionService sessions,
            SessionEventStore eventStore,
            ModelClient model,
            ToolRegistry tools,
            ApprovalPolicy approvalPolicy,
            ApprovalGate approvalGate,
            WeaverProperties properties,
            Supplier<SseEmitter> emitterFactory) {
        this(
                sessions,
                eventStore,
                model,
                tools,
                approvalPolicy,
                approvalGate,
                properties.maxSteps(),
                emitterFactory);
    }

    private RunService(
            SessionService sessions,
            SessionEventStore eventStore,
            ModelClient model,
            ToolRegistry tools,
            ApprovalPolicy approvalPolicy,
            ApprovalGate approvalGate,
            int maxSteps,
            Supplier<SseEmitter> emitterFactory) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
        this.model = Objects.requireNonNull(model, "model");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        this.approvalGate = Objects.requireNonNull(approvalGate, "approvalGate");
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        this.maxSteps = maxSteps;
        this.emitterFactory = Objects.requireNonNull(emitterFactory, "emitterFactory");
    }

    /**
     * 异步执行一轮会话，并通过 SSE 转发模型实时事件。
     *
     * <p>方法先验证会话存在，再创建无超时的响应发射器，并在虚拟线程中用完整协作者构造
     * 本次专用的 {@link RunLoop}。运行正常结束后完成发射器；未收敛异常通过
     * {@code completeWithError} 结束响应。返回时模型可能仍在运行。
     *
     * @param sessionId 目标会话标识
     * @param content 本轮用户输入
     * @return 当前请求的 SSE 发射器
     * @throws ResponseStatusException 会话不存在时返回 404
     */
    public SseEmitter run(String sessionId, String content) {
        if (sessions.find(sessionId).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "session not found: " + sessionId);
        }

        SseEmitter emitter = emitterFactory.get();
        SseStreamSink streamSink = new SseStreamSink(emitter);
        Thread.ofVirtual()
                .name("weaver-run-" + sessionId)
                .start(() -> execute(sessionId, content, streamSink, emitter));
        return emitter;
    }

    private void execute(
            String sessionId,
            String content,
            SseStreamSink streamSink,
            SseEmitter emitter) {
        try {
            new RunLoop(
                            model,
                            eventStore,
                            maxSteps,
                            streamSink,
                            tools,
                            approvalPolicy,
                            approvalGate)
                    .run(sessionId, content);
            emitter.complete();
        } catch (Exception exception) {
            // 运行失败要让客户端和服务端都能看到：先推 error 事件，再结束响应。
            log.error("run failed: session={}", sessionId, exception);
            try {
                streamSink.onError(String.valueOf(exception.getMessage()));
            } catch (RuntimeException sendFailure) {
                // 客户端可能已经断开；错误已记录，清理路径不再抛出。
                log.debug("failed to send error event: session={}", sessionId, sendFailure);
            }
            emitter.completeWithError(exception);
        }
    }
}
