package cn.yhjyhj.weaver.server.service;

import cn.yhjyhj.weaver.core.approval.ApprovalGrantStore;
import cn.yhjyhj.weaver.core.approval.ApprovalPolicy;
import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.SessionEventStore;
import cn.yhjyhj.weaver.core.loop.RunLoop;
import cn.yhjyhj.weaver.core.loop.RunLoopConfig;
import cn.yhjyhj.weaver.core.loop.context.Inbox;
import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import cn.yhjyhj.weaver.server.api.dto.InboxEntryView;
import cn.yhjyhj.weaver.server.settings.ModelClientProvider;
import cn.yhjyhj.weaver.server.sse.SseApprovalGate;
import cn.yhjyhj.weaver.server.sse.SseChannel;
import cn.yhjyhj.weaver.server.sse.SseStreamSink;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
    private final ModelClientProvider modelProvider; // 每轮解析当前模型的入口
    private final SessionTitleGenerator titleGenerator; // 首轮后生成会话标题
    private final ToolRegistryFactory toolRegistryFactory; // 按会话工作区构建工具
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
        this.modelProvider = validatedDependencies.modelProvider();
        this.titleGenerator = validatedDependencies.titleGenerator();
        this.toolRegistryFactory = validatedDependencies.toolRegistryFactory();
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
        requireSession(sessionId);
        if (activeRuns.containsKey(sessionId)) {
            throw conflict(sessionId);
        }

        SseEmitter emitter = emitterFactory.get();
        SseChannel channel = SseChannel.bind(emitter);
        SseApprovalGate gate = channel.approvalGate(approvalTimeout);
        ActiveRun activeRun = new ActiveRun(gate, channel);
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
     * 向活动运行投递一条待消费输入，并立即把新的信箱快照推给当前 SSE 响应。
     *
     * @param sessionId 会话标识
     * @param content 非空白用户输入
     * @param target 输入生效目标
     * @return 成功创建的待消费条目
     * @throws ResponseStatusException 会话不存在时返回 404；运行未挂接或投递失败时返回
     *     409；信箱已满时返回 429
     */
    public Inbox.Entry enqueue(
            String sessionId, String content, Inbox.Target target) {
        requireSession(sessionId);
        ActiveRun activeRun = activeRunWithLoop(sessionId);
        RunLoop loop = activeRun.loop().get();
        if (loop.pendingInputs().size() >= RunLoop.DEFAULT_INBOX_CAPACITY) {
            throw inboxFull(sessionId);
        }
        Inbox.Entry entry = loop.enqueueEntry(content, target);
        if (entry == null) {
            throw noActiveRun(sessionId);
        }
        publishInbox(activeRun);
        return entry;
    }

    /**
     * 返回活动运行当前未消费的信箱条目。
     *
     * <p>无活动运行或运行尚未挂接时返回空列表，便于客户端恢复界面。
     *
     * @param sessionId 会话标识
     * @return 按到达顺序排列的只读快照
     * @throws ResponseStatusException 会话不存在时返回 404
     */
    public List<Inbox.Entry> pending(String sessionId) {
        requireSession(sessionId);
        ActiveRun activeRun = activeRuns.get(sessionId);
        if (activeRun == null) {
            return List.of();
        }
        RunLoop loop = activeRun.loop().get();
        return loop == null ? List.of() : loop.pendingInputs();
    }

    /**
     * 撤回活动运行中的一条待消费输入，并推送最新快照。
     *
     * @param sessionId 会话标识
     * @param id 待撤回条目标识
     * @return 条目存在并成功撤回时为 true；未知条目为 false
     * @throws ResponseStatusException 会话不存在时返回 404；没有活动运行时返回 409
     */
    public boolean removePending(String sessionId, String id) {
        requireSession(sessionId);
        ActiveRun activeRun = activeRunWithLoop(sessionId);
        RunLoop loop = activeRun.loop().get();
        if (!loop.removePending(id)) {
            return false;
        }
        publishInbox(activeRun);
        return true;
    }

    /**
     * 修改活动运行中的一条待消费输入，并推送最新快照。
     *
     * @param sessionId 会话标识
     * @param id 待修改条目标识
     * @param content 替换后的非空白文本
     * @return 替换后的条目；未知条目或空白文本时为 null
     * @throws ResponseStatusException 会话不存在时返回 404；没有活动运行时返回 409
     */
    public Inbox.Entry replacePending(String sessionId, String id, String content) {
        requireSession(sessionId);
        ActiveRun activeRun = activeRunWithLoop(sessionId);
        Inbox.Entry entry = activeRun.loop().get().replacePending(id, content);
        if (entry != null) {
            publishInbox(activeRun);
        }
        return entry;
    }

    /**
     * 请求中止指定会话的活动运行。
     *
     * <p>取消会立即传递给 {@link RunLoop}，并通过已注册回调关闭当前模型流订阅；
     * 循环在当前步骤释放后判定中止。正在执行的工具和等待中的审批仍在步边界处理。
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

    /**
     * 判断指定会话是否有活动运行。
     *
     * @param sessionId 会话标识
     * @return 当前存在活动运行时为 true
     */
    public boolean isRunning(String sessionId) {
        return activeRuns.containsKey(sessionId);
    }

    private void execute(
            String sessionId,
            String content,
            SseChannel channel,
            ActiveRun activeRun) {
        SseStreamSink streamSink = channel.streamSink();
        ModelClient model = modelProvider.current();
        Path root = sessions.workspace(sessionId)
                .orElseThrow(() -> new IllegalStateException(
                        "session workspace not found: " + sessionId));
        ToolRegistry tools = toolRegistryFactory.create(root);
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
        generateTitleIfNeeded(sessionId, channel);
        sessions.touch(sessionId);

        if (loop.finalPhase().aborted()) {
            boolean cancelled = activeRun.cancelRequested().get();
            // 只有用户取消和步数上限才算「中止」；循环内部异常同样会置 aborted，
            // 但那种情况已由 error 事件表达，不能再补一个误导性的 aborted。
            if (cancelled || loop.finalPhase().step() >= maxSteps) {
                channel.emit("aborted", new AbortedEvent(cancelled ? "cancelled" : "max-steps"));
            }
        }
    }

    private void generateTitleIfNeeded(String sessionId, SseChannel channel) {
        if (!sessions.isUnnamed(sessionId)) {
            return;
        }
        String firstUserMessage = firstUserMessage(sessionId);
        if (firstUserMessage == null) {
            return;
        }
        try {
            String title = titleGenerator.generate(modelProvider.current(), firstUserMessage);
            if (sessions.renameIfUnnamed(sessionId, title)) {
                channel.emit("session_title", new SessionTitleEvent(title));
            }
        } catch (RuntimeException exception) {
            log.debug("session title generation failed: session={}", sessionId, exception);
        }
    }

    private String firstUserMessage(String sessionId) {
        return sessions.events(sessionId).stream()
                .map(storedEvent -> storedEvent.event())
                .filter(SessionEvent.MessageAdded.class::isInstance)
                .map(SessionEvent.MessageAdded.class::cast)
                .filter(message -> message.role() == SessionEvent.Role.USER)
                .map(SessionEvent.MessageAdded::content)
                .findFirst()
                .orElse(null);
    }

    private static ResponseStatusException conflict(String sessionId) {
        return new ResponseStatusException(
                HttpStatus.CONFLICT, "run already active: " + sessionId);
    }

    private void requireSession(String sessionId) {
        if (sessions.find(sessionId).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "session not found: " + sessionId);
        }
    }

    private ActiveRun activeRunWithLoop(String sessionId) {
        ActiveRun activeRun = activeRuns.get(sessionId);
        if (activeRun == null || activeRun.loop().get() == null) {
            throw noActiveRun(sessionId);
        }
        return activeRun;
    }

    private static ResponseStatusException noActiveRun(String sessionId) {
        return new ResponseStatusException(
                HttpStatus.CONFLICT, "no active run: " + sessionId);
    }

    private static ResponseStatusException inboxFull(String sessionId) {
        return new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, "inbox is full: " + sessionId);
    }

    private static void publishInbox(ActiveRun activeRun) {
        RunLoop loop = activeRun.loop().get();
        if (loop == null) {
            return;
        }
        List<InboxEntryView> entries = loop.pendingInputs().stream()
                .map(InboxEntryView::from)
                .toList();
        activeRun.channel().emit("inbox", entries);
    }

    /** 单个会话的活动运行及其取消状态。 */
    private static final class ActiveRun {

        /** 等待用户审批的 SSE 闸门。 */
        private final SseApprovalGate gate;
        /** 当前运行绑定的 SSE 响应通道，用于推送信箱快照。 */
        private final SseChannel channel;
        /** 是否由用户主动请求取消，用于区分用户取消与步数上限。 */
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        /** 虚拟线程创建后挂接的运行循环；挂接前取消由取消位兜底。 */
        private final AtomicReference<RunLoop> loop = new AtomicReference<>();

        private ActiveRun(SseApprovalGate gate, SseChannel channel) {
            this.gate = Objects.requireNonNull(gate, "gate");
            this.channel = Objects.requireNonNull(channel, "channel");
        }

        private SseApprovalGate gate() {
            return gate;
        }

        private SseChannel channel() {
            return channel;
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

    /** 自动生成并已持久化的会话标题。 */
    private record SessionTitleEvent(String title) {}
}
