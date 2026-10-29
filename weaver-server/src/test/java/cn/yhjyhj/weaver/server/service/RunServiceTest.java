package cn.yhjyhj.weaver.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.approval.ApprovalGrantStore;
import cn.yhjyhj.weaver.core.approval.RuleBasedApprovalPolicy;
import cn.yhjyhj.weaver.core.events.InMemorySessionEventStore;
import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.SessionEventStore;
import cn.yhjyhj.weaver.core.events.SessionSnapshot;
import cn.yhjyhj.weaver.core.events.SessionEvents;
import cn.yhjyhj.weaver.core.events.StoredEvent;
import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import cn.yhjyhj.weaver.core.tool.Tool;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import cn.yhjyhj.weaver.server.api.dto.SessionSummary;
import cn.yhjyhj.weaver.server.model.EchoModelClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class RunServiceTest {

    @Test
    void runsLoopOnVirtualThreadAndCompletesSseEmitter() throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(store);
        SessionSummary summary = sessions.create("Echo");
        RecordingEmitter emitter = new RecordingEmitter();
        EchoModelClient echo = new EchoModelClient();
        AtomicBoolean executedOnVirtualThread = new AtomicBoolean();
        ModelClient virtualThreadProbe = request -> {
            executedOnVirtualThread.set(Thread.currentThread().isVirtual());
            return echo.stream(request);
        };
        RunService service = new RunService(dependencies(
                sessions,
                store,
                virtualThreadProbe,
                ToolRegistry.empty(),
                300,
                () -> emitter));

        SseEmitter returned = service.run(summary.id(), "hello");

        assertTrue(emitter.awaitCompletion(Duration.ofSeconds(5)));
        assertEquals(emitter, returned);
        assertNull(emitter.failure);
        assertTrue(executedOnVirtualThread.get());
        SessionSnapshot snapshot = SessionEvents.replay(store.from(summary.id(), 0L));
        assertEquals(
                List.of(
                        new SessionSnapshot.Message(SessionEvent.Role.USER, "hello"),
                        new SessionSnapshot.Message(
                                SessionEvent.Role.ASSISTANT, "echo: hello")),
                snapshot.messages());
        assertTrue(store.from(summary.id(), 0L).stream()
                .map(StoredEvent::event)
                .anyMatch(SessionEvent.RunFinished.class::isInstance));
    }

    @Test
    void rejectsUnknownSessionBeforeCreatingEmitter() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(store);
        RunService service = new RunService(dependencies(
                sessions, store, new EchoModelClient(), ToolRegistry.empty(), 300));

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> service.run("missing", "hello"));

        assertEquals(HttpStatus.NOT_FOUND, thrown.getStatusCode());
    }

    @Test
    void resolvesApprovalForActiveGateAndContinuesRun() throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(store);
        SessionSummary summary = sessions.create("Approval");
        RecordingEmitter emitter = new RecordingEmitter();
        RunService service = new RunService(dependencies(
                sessions,
                store,
                approvalThenResponseModel(),
                new ToolRegistry(List.of(approvalTool())),
                5,
                () -> emitter));

        service.run(summary.id(), "hello");

        assertTrue(resolveWhenPending(service, summary.id(), "call-1", true));
        assertTrue(emitter.awaitCompletion(Duration.ofSeconds(5)));
        SessionSnapshot snapshot = SessionEvents.replay(store.from(summary.id(), 0L));
        assertEquals(
                List.of(new SessionSnapshot.ApprovalRecord(
                        "call-1", "file", true, "user")),
                snapshot.approvals());
        assertEquals(
                List.of(new SessionSnapshot.Message(
                        SessionEvent.Role.ASSISTANT, "approval accepted")),
                snapshot.messages().stream()
                        .filter(message -> message.role() == SessionEvent.Role.ASSISTANT)
                        .toList());
    }

    @Test
    void returnsFalseWhenResolvingForUnknownActiveGate() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(store);
        RunService service = new RunService(dependencies(
                sessions, store, new EchoModelClient(), ToolRegistry.empty(), 300));

        assertFalse(service.resolveApproval("missing-session", "call-1", true));
    }

    @Test
    void rejectsConcurrentRunForSameSession() throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(store);
        SessionSummary summary = sessions.create("Concurrent");
        RecordingEmitter emitter = new RecordingEmitter();
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        EchoModelClient echo = new EchoModelClient();
        ModelClient blockingModel = request -> {
            modelStarted.countDown();
            try {
                releaseModel.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return echo.stream(request);
        };
        RunService service = new RunService(dependencies(
                sessions,
                store,
                blockingModel,
                ToolRegistry.empty(),
                5,
                () -> emitter));

        service.run(summary.id(), "hello");
        assertTrue(modelStarted.await(2, TimeUnit.SECONDS));
        ResponseStatusException conflict = assertThrows(
                ResponseStatusException.class,
                () -> service.run(summary.id(), "again"));
        try {
            assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        } finally {
            releaseModel.countDown();
        }
        assertTrue(emitter.awaitCompletion(Duration.ofSeconds(5)));
    }

    @Test
    void completionOfSseDeniesPendingApprovalAndFinishesRun() throws Exception {
        assertSseTerminationDeniesPendingApproval(RecordingEmitter::complete);
    }

    @Test
    void timeoutOfSseDeniesPendingApprovalAndFinishesRun() throws Exception {
        assertSseTerminationDeniesPendingApproval(RecordingEmitter::timeout);
    }

    @Test
    void errorOfSseDeniesPendingApprovalAndFinishesRun() throws Exception {
        assertSseTerminationDeniesPendingApproval(
                emitter -> emitter.fail(new IllegalStateException("client disconnected")));
    }

    private static void assertSseTerminationDeniesPendingApproval(
            Consumer<RecordingEmitter> terminate) throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(store);
        SessionSummary summary = sessions.create("Disconnect");
        RecordingEmitter emitter = new RecordingEmitter();
        RunService service = new RunService(dependencies(
                sessions,
                store,
                approvalThenResponseModel(),
                new ToolRegistry(List.of(approvalTool())),
                30,
                () -> emitter));

        service.run(summary.id(), "hello");
        terminate.accept(emitter);

        assertTrue(awaitRunFinished(store, summary.id()));
        SessionSnapshot snapshot = SessionEvents.replay(store.from(summary.id(), 0L));
        assertEquals(
                List.of(new SessionSnapshot.ApprovalRecord(
                        "call-1", "file", false, "user-denied")),
                snapshot.approvals());
        assertEquals(1, snapshot.toolCalls().size());
        assertTrue(snapshot.toolCalls().getFirst().result().isError());
        assertEquals(
                "denied by user-denied",
                snapshot.toolCalls().getFirst().result().content());
    }

    private static RunServiceDependencies dependencies(
            SessionService sessions,
            SessionEventStore store,
            ModelClient model,
            ToolRegistry tools,
            int approvalTimeoutSeconds) {
        return dependencies(
                sessions,
                store,
                model,
                tools,
                approvalTimeoutSeconds,
                RunServiceDependencies.defaultEmitterFactory());
    }

    private static RunServiceDependencies dependencies(
            SessionService sessions,
            SessionEventStore store,
            ModelClient model,
            ToolRegistry tools,
            int approvalTimeoutSeconds,
            Supplier<SseEmitter> emitterFactory) {
        return new RunServiceDependencies(
                sessions,
                store,
                model,
                tools,
                RuleBasedApprovalPolicy.defaults(),
                ApprovalGrantStore.none(),
                10,
                Duration.ofSeconds(approvalTimeoutSeconds),
                emitterFactory);
    }

    private static ModelClient approvalThenResponseModel() {
        AtomicInteger calls = new AtomicInteger();
        StreamChunk.ToolCall call = new StreamChunk.ToolCall(
                "call-1", "file", "{\"op\":\"write\",\"path\":\"note.txt\",\"content\":\"x\"}");
        return request -> subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private final AtomicBoolean started = new AtomicBoolean(); // 每次模型调用只发一次流
            private final AtomicBoolean cancelled = new AtomicBoolean(); // 支持订阅取消

            @Override
            public void request(long count) {
                if (count <= 0L || cancelled.get() || !started.compareAndSet(false, true)) {
                    return;
                }
                if (calls.incrementAndGet() == 1) {
                    subscriber.onNext(call);
                } else {
                    subscriber.onNext(new StreamChunk.Text("approval accepted"));
                }
                subscriber.onNext(new StreamChunk.Done(StreamChunk.StopReason.STOP));
                subscriber.onComplete();
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        });
    }

    private static Tool approvalTool() {
        return new Tool() {
            @Override
            public String name() {
                return "file";
            }

            @Override
            public ToolResult execute(String argumentsJson) {
                return new ToolResult("executed", false, 0L);
            }

            @Override
            public Reversibility reversibility(String argumentsJson) {
                return Reversibility.RECOVERABLE;
            }
        };
    }

    private static boolean resolveWhenPending(
            RunService service, String sessionId, String callId, boolean approved)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (service.resolveApproval(sessionId, callId, approved)) {
                return true;
            }
            Thread.sleep(10L);
        }
        return false;
    }

    private static boolean awaitRunFinished(
            InMemorySessionEventStore store, String sessionId)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (store.from(sessionId, 0L).stream()
                    .map(StoredEvent::event)
                    .anyMatch(SessionEvent.RunFinished.class::isInstance)) {
                return true;
            }
            Thread.sleep(10L);
        }
        return false;
    }

    private static final class RecordingEmitter extends SseEmitter {

        private final CountDownLatch completion = new CountDownLatch(1); // 异步完成信号
        private volatile Throwable failure; // 异步失败原因
        private final List<Runnable> completionCallbacks = new CopyOnWriteArrayList<>(); // 完成回调
        private final List<Runnable> timeoutCallbacks = new CopyOnWriteArrayList<>(); // 超时回调
        private final List<Consumer<Throwable>> errorCallbacks = new CopyOnWriteArrayList<>(); // 错误回调

        private RecordingEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            // 事件内容由 SseStreamSinkTest 验证，这里只需观察异步生命周期。
        }

        @Override
        public void complete() {
            completionCallbacks.forEach(Runnable::run);
            completion.countDown();
        }

        @Override
        public void completeWithError(Throwable failure) {
            this.failure = failure;
            completion.countDown();
        }

        private void timeout() {
            timeoutCallbacks.forEach(Runnable::run);
            completion.countDown();
        }

        private void fail(Throwable failure) {
            this.failure = failure;
            errorCallbacks.forEach(callback -> callback.accept(failure));
            completion.countDown();
        }

        @Override
        public void onCompletion(Runnable callback) {
            completionCallbacks.add(callback);
        }

        @Override
        public void onTimeout(Runnable callback) {
            timeoutCallbacks.add(callback);
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            errorCallbacks.add(callback);
        }

        private boolean awaitCompletion(Duration timeout) throws InterruptedException {
            return completion.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
