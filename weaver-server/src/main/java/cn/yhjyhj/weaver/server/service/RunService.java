package cn.yhjyhj.weaver.server.service;

import cn.yhjyhj.weaver.core.approval.ApprovalGrantStore;
import cn.yhjyhj.weaver.core.approval.ApprovalPolicy;
import cn.yhjyhj.weaver.core.events.SessionEventStore;
import cn.yhjyhj.weaver.core.loop.RunLoop;
import cn.yhjyhj.weaver.core.loop.RunLoopConfig;
import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import cn.yhjyhj.weaver.server.sse.SseApprovalGate;
import cn.yhjyhj.weaver.server.sse.SseChannel;
import cn.yhjyhj.weaver.server.sse.SseStreamSink;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 为每次消息请求装配独立 RunLoop，并把实时输出桥接到 SSE。 */
@Slf4j
public final class RunService {

    private final SessionService sessions; // 会话存在性边界
    private final SessionEventStore eventStore; // 运行事实源
    private final ModelClient model; // 当前模型客户端
    private final ToolRegistry tools; // 进程级工具注册表
    private final ApprovalPolicy approvalPolicy; // 工具审批策略
    private final ApprovalGrantStore approvalGrants; // 会话审批授权记录
    private final int maxSteps; // 单轮运行的最大步数
    private final Duration approvalTimeout; // 单次交互审批等待上限
    private final Supplier<SseEmitter> emitterFactory; // 单次请求的响应发射器工厂
    private final Map<String, ActiveRun> activeRuns = // 每会话当前活动运行
            new ConcurrentHashMap<>();

    /**
     * 使用进程级协作者与单次运行参数创建运行服务。
     *
     * @param dependencies 会话、模型、工具、审批与 SSE 依赖
     */
    public RunService(RunServiceDependencies dependencies) {
        RunServiceDependencies validatedDependencies =
                Objects.requireNonNull(dependencies, "dependencies");
        this.sessions = validatedDependencies.sessions();
        this.eventStore = validatedDependencies.eventStore();
        this.model = validatedDependencies.model();
        this.tools = validatedDependencies.tools();
        this.approvalPolicy = validatedDependencies.approvalPolicy();
        this.approvalGrants = validatedDependencies.approvalGrants();
        this.maxSteps = validatedDependencies.maxSteps();
        this.approvalTimeout = validatedDependencies.approvalTimeout();
        this.emitterFactory = validatedDependencies.emitterFactory();
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
     * @throws ResponseStatusException 会话不存在时返回 404；同一会话已有运行时返回 409
     */
    public SseEmitter run(String sessionId, String content) {
        if (sessions.find(sessionId).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "session not found: " + sessionId);
        }
        if (activeRuns.containsKey(sessionId)) {
            throw conflict(sessionId);
        }

        SseEmitter emitter = emitterFactory.get();
        SseChannel channel = SseChannel.bind(emitter);
        SseApprovalGate gate = channel.approvalGate(approvalTimeout);
        ActiveRun activeRun = new ActiveRun(gate);
        emitter.onCompletion(gate::cancelAll);
        emitter.onTimeout(gate::cancelAll);
        emitter.onError(ignored -> gate.cancelAll());

        // 原子占位保证并发请求只有一个能启动；失败者的 gate 不会等待任何审批。
        if (activeRuns.putIfAbsent(sessionId, activeRun) != null) {
            gate.cancelAll();
            throw conflict(sessionId);
        }

        try {
            Thread.ofVirtual()
                    .name("weaver-run-" + sessionId)
                    .start(() -> {
                        Throwable failure = null;
                        try {
                            execute(sessionId, content, channel, activeRun);
                        } catch (Exception exception) {
                            failure = exception;
                        } finally {
                            // 先撤销活动运行，再完成响应；客户端收到结束时取消必然返回 false。
                            activeRuns.remove(sessionId, activeRun);
                            gate.cancelAll();
                        }
                        if (failure == null) {
                            emitter.complete();
                            return;
                        }

                        // 运行失败要让客户端和服务端都能看到：先推 error，再结束响应。
                        log.error("run failed: session={}", sessionId, failure);
                        try {
                            channel.streamSink().onError(String.valueOf(failure.getMessage()));
                        } catch (RuntimeException sendFailure) {
                            // 客户端可能已经断开；错误已记录，清理路径不再抛出。
                            log.debug(
                                    "failed to send error event: session={}",
                                    sessionId,
                                    sendFailure);
                        }
                        emitter.completeWithError(failure);
                    });
        } catch (RuntimeException | Error exception) {
            activeRuns.remove(sessionId, activeRun);
            gate.cancelAll();
            throw exception;
        }
        return emitter;
    }

    /**
     * 将用户决策提交给指定会话的当前运行。
     *
     * @param sessionId 会话标识
     * @param callId 工具调用标识
     * @param approved 用户是否批准
     * @return 找到并接受决策时返回 true
     */
    public boolean resolveApproval(String sessionId, String callId, boolean approved) {
        ActiveRun activeRun = activeRuns.get(sessionId);
        return activeRun != null && activeRun.gate().resolve(callId, approved);
    }

    /**
     * 请求中止指定会话的活动运行。
     *
     * <p>取消会立即传递给 {@link RunLoop}，但循环只会在当前步骤结束后的边界检查中退出，
     * 已在途的模型 HTTP 请求不会被强行中断。
     *
     * @param sessionId 会话标识
     * @return 找到并标记活动运行时返回 true；没有活动运行时返回 false
     */
    public boolean cancel(String sessionId) {
        ActiveRun activeRun = activeRuns.get(sessionId);
        if (activeRun == null) {
            return false;
        }

        activeRun.cancelRequested().set(true);
        RunLoop loop = activeRun.loop().get();
        if (loop != null) {
            loop.cancel();
        }
        return true;
    }

    private void execute(
            String sessionId,
            String content,
            SseChannel channel,
            ActiveRun activeRun) {
        SseStreamSink streamSink = channel.streamSink();
        RunLoop loop = new RunLoop(
                model,
                channel.withToolResultRelay(eventStore),
                new RunLoopConfig(
                        maxSteps,
                        streamSink,
                        tools,
                        approvalPolicy,
                        activeRun.gate(),
                        approvalGrants));
        activeRun.loop().set(loop);

        // 先挂接再检查取消位，覆盖 HTTP 取消在 loop 创建前到达的窗口。
        if (activeRun.cancelRequested().get()) {
            loop.cancel();
        }
        loop.run(sessionId, content);

        if (loop.finalPhase().aborted()) {
            boolean cancelled = activeRun.cancelRequested().get();
            // 只有用户取消和步数上限才算「中止」；循环内部异常同样会置 aborted，
            // 但那种情况已由 error 事件表达，不能再补一个误导性的 aborted。
            if (cancelled || loop.finalPhase().step() >= maxSteps) {
                channel.emit("aborted", new AbortedEvent(cancelled ? "cancelled" : "max-steps"));
            }
        }
    }

    private static ResponseStatusException conflict(String sessionId) {
        return new ResponseStatusException(
                HttpStatus.CONFLICT, "run already active: " + sessionId);
    }

    /** 单个会话的活动运行及其取消状态。 */
    private static final class ActiveRun {

        /** 等待用户审批的 SSE 闸门。 */
        private final SseApprovalGate gate;
        /** 是否由用户主动请求取消，用于区分用户取消与步数上限。 */
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        /** 虚拟线程创建后挂接的运行循环；挂接前取消由取消位兜底。 */
        private final AtomicReference<RunLoop> loop = new AtomicReference<>();

        private ActiveRun(SseApprovalGate gate) {
            this.gate = Objects.requireNonNull(gate, "gate");
        }

        private SseApprovalGate gate() {
            return gate;
        }

        private AtomicBoolean cancelRequested() {
            return cancelRequested;
        }

        private AtomicReference<RunLoop> loop() {
            return loop;
        }
    }

    /** 发送给前端的运行中止原因。 */
    private record AbortedEvent(String reason) {}
}
