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
import cn.yhjyhj.weaver.core.loop.context.Inbox;
import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import cn.yhjyhj.weaver.core.tool.Tool;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import cn.yhjyhj.weaver.server.api.dto.SessionSummary;
import cn.yhjyhj.weaver.server.model.EchoModelClient;
import cn.yhjyhj.weaver.server.settings.ModelClientProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
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

    private static final ObjectMapper JSON = new ObjectMapper(); // 验证取消 SSE 的载荷字段

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
                ModelClientProvider.fixed(virtualThreadProbe),
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
                sessions,
                store,
                ModelClientProvider.fixed(new EchoModelClient()),
                ToolRegistry.empty(),
                300));

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
                ModelClientProvider.fixed(approvalThenResponseModel()),
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
                sessions,
                store,
                ModelClientProvider.fixed(new EchoModelClient()),
                ToolRegistry.empty(),
                300));

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
                ModelClientProvider.fixed(blockingModel),
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
    void returnsFalseWhenCancellingWithoutActiveRun() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(store);
        RunService service = new RunService(dependencies(
                sessions,
                store,
                ModelClientProvider.fixed(new EchoModelClient()),
                ToolRegistry.empty(),
                300));

        assertFalse(service.cancel("missing-session"));
    }

    @Test
    void enqueuesTurnInputAndProcessesItAsTheNextUserMessage() throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(store);
        SessionSummary summary = sessions.create("Inbox turn");
        RecordingEmitter emitter = new RecordingEmitter();
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        RunService service = new RunService(dependencies(
                sessions,
                store,
                ModelClientProvider.fixed(blockingFirstCall(
                        new EchoModelClient(), modelStarted, releaseModel)),
                ToolRegistry.empty(),
                30,
                () -> emitter));

        service.run(summary.id(), "first");
        assertTrue(modelStarted.await(2, TimeUnit.SECONDS));
        Inbox.Entry queued;
        try {
            queued = service.enqueue(
                    summary.id(), "only change main", Inbox.Target.TURN);
        } finally {
            releaseModel.countDown();
        }

        assertTrue(emitter.awaitCompletion(Duration.ofSeconds(5)));
        assertEquals(Inbox.Target.TURN, queued.target());
        assertEquals("only change main", queued.content());
        assertEquals(
                List.of("first", "only change main"),
                SessionEvents.replay(store.from(summary.id(), 0L)).messages().stream()
                        .filter(message -> message.role() == SessionEvent.Role.USER)
                        .map(SessionSnapshot.Message::content)
                        .toList());
    }

    @Test
    void enqueuesStepInputIntoTheEventStreamBeforeTheNextStep() throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(store);
        SessionSummary summary = sessions.create("Inbox step");
        RecordingEmitter emitter = new RecordingEmitter();
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = request -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                modelStarted.countDown();
                awaitLatch(releaseModel);
                return publisherOf(
                        new StreamChunk.ToolCall("call-1", "file", "{}"),
                        new StreamChunk.Done(StreamChunk.StopReason.STOP));
            }
            return publisherOf(
                    new StreamChunk.Text("steered"),
                    new StreamChunk.Done(StreamChunk.StopReason.STOP));
        };
        RunService service = new RunService(dependencies(
                sessions,
                store,
                ModelClientProvider.fixed(model),
                new ToolRegistry(List.of(reversibleFileTool())),
                30,
                () -> emitter));

        service.run(summary.id(), "first");
        assertTrue(modelStarted.await(2, TimeUnit.SECONDS));
        try {
            assertEquals(
                    Inbox.Target.STEP,
                    service.enqueue(summary.id(), "only main", Inbox.Target.STEP).target());
        } finally {
            releaseModel.countDown();
        }

        assertTrue(awaitRunFinished(store, summary.id()));
        assertTrue(emitter.awaitCompletion(Duration.ofSeconds(1)));
        List<SessionEvent> trace = store.from(summary.id(), 0L).stream()
                .map(StoredEvent::event)
                .filter(event -> event instanceof SessionEvent.MessageAdded
                        || event instanceof SessionEvent.ToolCallStarted
                        || event instanceof SessionEvent.ToolCallCompleted
                        || event instanceof SessionEvent.RunFinished)
                .toList();
        assertEquals(
                new SessionEvent.MessageAdded(SessionEvent.Role.USER, "first"),
                trace.get(0));
        assertTrue(trace.get(1) instanceof SessionEvent.ToolCallStarted);
        assertTrue(trace.get(2) instanceof SessionEvent.ToolCallCompleted);
        assertEquals(
                new SessionEvent.MessageAdded(SessionEvent.Role.USER, "only main"),
                trace.get(3));
        assertEquals(
                new SessionEvent.MessageAdded(SessionEvent.Role.ASSISTANT, "steered"),
                trace.get(4));
        assertEquals(new SessionEvent.RunFinished(false), trace.get(5));
    }

    @Test
    void pendingReflectsRemovedAndReplacedEntries() throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(store);
        SessionSummary summary = sessions.create("Inbox edit");
        RecordingEmitter emitter = new RecordingEmitter();
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        RunService service = new RunService(dependencies(
                sessions,
                store,
                ModelClientProvider.fixed(blockingFirstCall(
                        new EchoModelClient(), modelStarted, releaseModel)),
                ToolRegistry.empty(),
                30,
                () -> emitter));

        service.run(summary.id(), "first");
        assertTrue(modelStarted.await(2, TimeUnit.SECONDS));
        try {
            Inbox.Entry first = service.enqueue(
                    summary.id(), "remove me", Inbox.Target.STEP);
            Inbox.Entry second = service.enqueue(
                    summary.id(), "replace me", Inbox.Target.TURN);

            assertTrue(service.removePending(summary.id(), first.id()));
            assertEquals(
                    new Inbox.Entry(second.id(), Inbox.Target.TURN, "updated"),
                    service.replacePending(summary.id(), second.id(), "updated"));
            assertEquals(
                    List.of(new Inbox.Entry(
                            second.id(), Inbox.Target.TURN, "updated")),
                    service.pending(summary.id()));
        } finally {
            releaseModel.countDown();
        }

        assertTrue(emitter.awaitCompletion(Duration.ofSeconds(5)));
    }

    @Test
    void rejectsInboxMutationsWithoutActiveRunAndReturnsEmptyPendingList() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(store);
        SessionSummary summary = sessions.create("Idle inbox");
        RunService service = new RunService(dependencies(
                sessions,
                store,
                ModelClientProvider.fixed(new EchoModelClient()),
                ToolRegistry.empty(),
                30));

        ResponseStatusException enqueueConflict = assertThrows(
                ResponseStatusException.class,
                () -> service.enqueue(summary.id(), "queued", Inbox.Target.TURN));
        ResponseStatusException removeConflict = assertThrows(
                ResponseStatusException.class,
                () -> service.removePending(summary.id(), "in-1"));
        ResponseStatusException replaceConflict = assertThrows(
                ResponseStatusException.class,
                () -> service.replacePending(summary.id(), "in-1", "updated"));
        assertEquals(HttpStatus.CONFLICT, enqueueConflict.getStatusCode());
        assertEquals(HttpStatus.CONFLICT, removeConflict.getStatusCode());
        assertEquals(HttpStatus.CONFLICT, replaceConflict.getStatusCode());
        assertEquals(List.of(), service.pending(summary.id()));
    }

    @Test
    void rejectsInboxDeliveryWhenInboxIsFull() throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(store);
        SessionSummary summary = sessions.create("Full inbox");
        RecordingEmitter emitter = new RecordingEmitter();
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        RunService service = new RunService(dependencies(
                sessions,
                store,
                ModelClientProvider.fixed(blockingFirstCall(
                        new EchoModelClient(), modelStarted, releaseModel)),
                ToolRegistry.empty(),
                30,
                () -> emitter));

        service.run(summary.id(), "first");
        assertTrue(modelStarted.await(2, TimeUnit.SECONDS));
        try {
            for (int index = 0; index < 32; index++) {
                service.enqueue(summary.id(), "queued " + index, Inbox.Target.TURN);
            }
            ResponseStatusException full = assertThrows(
                    ResponseStatusException.class,
                    () -> service.enqueue(summary.id(), "overflow", Inbox.Target.TURN));
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, full.getStatusCode());
            assertEquals(32, service.pending(summary.id()).size());
        } finally {
            releaseModel.countDown();
        }

        assertTrue(emitter.awaitCompletion(Duration.ofSeconds(5)));
    }

    @Test
    void publishesInboxSnapshotAfterSuccessfulDelivery() throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(store);
        SessionSummary summary = sessions.create("Inbox SSE");
        RecordingEmitter emitter = new RecordingEmitter();
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        RunService service = new RunService(dependencies(
                sessions,
                store,
                ModelClientProvider.fixed(blockingFirstCall(
                        new EchoModelClient(), modelStarted, releaseModel)),
                ToolRegistry.empty(),
                30,
                () -> emitter));

        service.run(summary.id(), "first");
        assertTrue(modelStarted.await(2, TimeUnit.SECONDS));
        try {
            service.enqueue(summary.id(), "fix main", Inbox.Target.STEP);

            SentEvent inbox = emitter.sentEvents().stream()
                    .filter(event -> event.name().equals("inbox"))
                    .findFirst()
                    .orElseThrow();
            List<Map<String, Object>> payload = JSON.convertValue(
                    inbox.data(), new TypeReference<>() {});
            assertEquals(1, payload.size());
            assertEquals("in-1", payload.getFirst().get("id"));
            assertEquals("STEP", payload.getFirst().get("target"));
            assertEquals("fix main", payload.getFirst().get("content"));
        } finally {
            releaseModel.countDown();
        }

        assertTrue(emitter.awaitCompletion(Duration.ofSeconds(5)));
    }

    @Test
    void cancelsActiveRunAndEmitsAbortedEvent() throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(store);
        SessionSummary summary = sessions.create("Cancel");
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
                ModelClientProvider.fixed(blockingModel),
                ToolRegistry.empty(),
                30,
                () -> emitter));

        service.run(summary.id(), "hello");
        assertTrue(modelStarted.await(2, TimeUnit.SECONDS));
        assertTrue(service.cancel(summary.id()));
        releaseModel.countDown();

        assertTrue(emitter.awaitCompletion(Duration.ofSeconds(5)));
        SessionSnapshot snapshot = SessionEvents.replay(store.from(summary.id(), 0L));
        assertTrue(snapshot.aborted());
        SentEvent aborted = emitter.sentEvents().stream()
                .filter(event -> event.name().equals("aborted"))
                .findFirst()
                .orElseThrow();
        Map<String, Object> payload = JSON.convertValue(
                aborted.data(), new TypeReference<>() {});
        assertEquals("cancelled", payload.get("reason"));
    }

    @Test
    void cancelsNeverEndingModelStreamAndCompletesWithAbortedEvent() throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(store);
        SessionSummary summary = sessions.create("Streaming cancel");
        RecordingEmitter emitter = new RecordingEmitter();
        CountDownLatch modelStarted = new CountDownLatch(1);
        AtomicBoolean subscriptionCancelled = new AtomicBoolean();
        ModelClient neverEndingModel = request -> subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                private final AtomicBoolean emitted = new AtomicBoolean();

                @Override
                public void request(long count) {
                    if (emitted.compareAndSet(false, true)) {
                        subscriber.onNext(new StreamChunk.Text("partial"));
                    }
                }

                @Override
                public void cancel() {
                    subscriptionCancelled.set(true);
                }
            });
            modelStarted.countDown();
        };
        RunService service = new RunService(dependencies(
                sessions,
                store,
                ModelClientProvider.fixed(neverEndingModel),
                ToolRegistry.empty(),
                30,
                () -> emitter));

        service.run(summary.id(), "hello");
        assertTrue(modelStarted.await(2, TimeUnit.SECONDS));
        assertTrue(service.cancel(summary.id()));

        assertTrue(emitter.awaitCompletion(Duration.ofSeconds(2)));
        assertTrue(subscriptionCancelled.get());
        SessionSnapshot snapshot = SessionEvents.replay(store.from(summary.id(), 0L));
        assertTrue(snapshot.aborted());
        SentEvent aborted = emitter.sentEvents().stream()
                .filter(event -> event.name().equals("aborted"))
                .findFirst()
                .orElseThrow();
        Map<String, Object> payload = JSON.convertValue(
                aborted.data(), new TypeReference<>() {});
        assertEquals("cancelled", payload.get("reason"));
    }

    @Test
    void doesNotEmitAbortedWhenTheLoopFailsInternally() throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(store);
        SessionSummary summary = sessions.create("Failed");
        RecordingEmitter emitter = new RecordingEmitter();
        ModelClient failingModel = request -> {
            throw new IllegalStateException("boom");
        };
        RunService service = new RunService(dependencies(
                sessions,
                store,
                ModelClientProvider.fixed(failingModel),
                ToolRegistry.empty(),
                30,
                () -> emitter));

        service.run(summary.id(), "hello");

        assertTrue(emitter.awaitCompletion(Duration.ofSeconds(5)));
        assertTrue(
                emitter.sentEvents().stream().noneMatch(event -> event.name().equals("aborted")));
    }

    @Test
    void returnsFalseWhenCancellingAfterRunCompletes() throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        SessionService sessions = new SessionService(store);
        SessionSummary summary = sessions.create("Completed");
        RecordingEmitter emitter = new RecordingEmitter();
        RunService service = new RunService(dependencies(
                sessions,
                store,
                ModelClientProvider.fixed(new EchoModelClient()),
                ToolRegistry.empty(),
                30,
                () -> emitter));

        service.run(summary.id(), "hello");

        assertTrue(emitter.awaitCompletion(Duration.ofSeconds(5)));
        assertFalse(service.cancel(summary.id()));
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
                ModelClientProvider.fixed(approvalThenResponseModel()),
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
            ModelClientProvider modelProvider,
            ToolRegistry tools,
            int approvalTimeoutSeconds) {
        return dependencies(
                sessions,
                store,
                modelProvider,
                tools,
                approvalTimeoutSeconds,
                RunServiceDependencies.defaultEmitterFactory());
    }

    private static RunServiceDependencies dependencies(
            SessionService sessions,
            SessionEventStore store,
            ModelClientProvider modelProvider,
            ToolRegistry tools,
            int approvalTimeoutSeconds,
            Supplier<SseEmitter> emitterFactory) {
        return new RunServiceDependencies(
                sessions,
                store,
                modelProvider,
                tools,
                RuleBasedApprovalPolicy.defaults(),
                ApprovalGrantStore.none(),
                10,
                Duration.ofSeconds(approvalTimeoutSeconds),
                emitterFactory);
    }

    private static ModelClient blockingFirstCall(
            ModelClient delegate,
            CountDownLatch started,
            CountDownLatch release) {
        AtomicBoolean firstCall = new AtomicBoolean(true);
        return request -> {
            if (firstCall.compareAndSet(true, false)) {
                started.countDown();
                awaitLatch(release);
            }
            return delegate.stream(request);
        };
    }

    private static Flow.Publisher<StreamChunk> publisherOf(StreamChunk... chunks) {
        return subscriber -> {
            SubmissionPublisher<StreamChunk> publisher = new SubmissionPublisher<>();
            publisher.subscribe(subscriber);
            for (StreamChunk chunk : chunks) {
                publisher.submit(chunk);
            }
            publisher.close();
        };
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for model release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
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

    private static Tool reversibleFileTool() {
        return new Tool() {
            @Override
            public String name() {
                return "file";
            }

            @Override
            public ToolResult execute(String argumentsJson) {
                return new ToolResult("checked", false, 0L);
            }

            @Override
            public Reversibility reversibility(String argumentsJson) {
                return Reversibility.REVERSIBLE;
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
        private final List<SentEvent> sentEvents = new CopyOnWriteArrayList<>(); // 已发送事件
        private final List<Runnable> completionCallbacks = new CopyOnWriteArrayList<>(); // 完成回调
        private final List<Runnable> timeoutCallbacks = new CopyOnWriteArrayList<>(); // 超时回调
        private final List<Consumer<Throwable>> errorCallbacks = new CopyOnWriteArrayList<>(); // 错误回调

        private RecordingEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            String eventName = null;
            Object payload = null;
            for (SseEmitter.DataWithMediaType part : builder.build()) {
                if (part.getData() instanceof String text && text.startsWith("event:")) {
                    eventName = text.lines()
                            .findFirst()
                            .orElseThrow()
                            .substring("event:".length());
                } else if (payload == null && !String.valueOf(part.getData()).isBlank()) {
                    payload = part.getData();
                }
            }
            if (eventName != null) {
                sentEvents.add(new SentEvent(eventName, payload));
            }
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

        private List<SentEvent> sentEvents() {
            return sentEvents;
        }
    }

    private record SentEvent(String name, Object data) {}
}
