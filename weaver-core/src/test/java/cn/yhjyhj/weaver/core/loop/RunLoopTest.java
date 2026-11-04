package cn.yhjyhj.weaver.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.approval.ApprovalDecision;
import cn.yhjyhj.weaver.core.approval.ApprovalGate;
import cn.yhjyhj.weaver.core.approval.ApprovalGrantStore;
import cn.yhjyhj.weaver.core.approval.ApprovalPolicy;
import cn.yhjyhj.weaver.core.approval.ApprovalResult;
import cn.yhjyhj.weaver.core.approval.RuleBasedApprovalPolicy;
import cn.yhjyhj.weaver.core.events.InMemorySessionEventStore;
import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.StoredEvent;
import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.loop.context.Inbox;
import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.model.ModelMessage;
import cn.yhjyhj.weaver.core.model.ModelRequest;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.model.StreamSink;
import cn.yhjyhj.weaver.core.phase.Phase;
import cn.yhjyhj.weaver.core.tool.Tool;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import cn.yhjyhj.weaver.core.tool.ToolSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RunLoopTest {

    private static final String SESSION_ID = "session";
    private static final ApprovalPolicy ALLOW_ALL =
            (call, reversibility, context) ->
                    new ApprovalResult(ApprovalDecision.ALLOW, "allowed"); // 执行型测试显式放行

    @Test
    void recordsUserAndAssistantMessagesThenFinishesNaturally() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        ScriptedModelClient model = new ScriptedModelClient(List.of(response(
                new StreamChunk.Text("hi"),
                new StreamChunk.Done(StreamChunk.StopReason.STOP))), null);
        RunLoop runLoop = new RunLoop(model, store);

        runLoop.run(SESSION_ID, "你好");

        assertEquals(List.of(
                new SessionEvent.MessageAdded(SessionEvent.Role.USER, "你好"),
                new SessionEvent.MessageAdded(SessionEvent.Role.ASSISTANT, "hi"),
                new SessionEvent.RunFinished(false)), events(store));
        assertTrue(runLoop.finalPhase() instanceof Phase.Idle);
    }

    @Test
    void processesInputEnqueuedDuringFirstTurnAsSecondTurn() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        AtomicReference<RunLoop> loopReference = new AtomicReference<>();
        ModelClient model = request -> {
            if ("first".equals(request.input())) {
                assertTrue(loopReference.get().enqueue("second"));
                assertFalse(loopReference.get().enqueue(" \t"));
            }
            return publisherOf(
                    new StreamChunk.Text(request.input().equals("first") ? "reply 1" : "reply 2"),
                    new StreamChunk.Done(StreamChunk.StopReason.STOP));
        };
        RunLoop runLoop = new RunLoop(model, store);
        loopReference.set(runLoop);

        runLoop.run(SESSION_ID, "first");

        assertEquals(List.of(
                new SessionEvent.MessageAdded(SessionEvent.Role.USER, "first"),
                new SessionEvent.MessageAdded(SessionEvent.Role.ASSISTANT, "reply 1"),
                new SessionEvent.MessageAdded(SessionEvent.Role.USER, "second"),
                new SessionEvent.MessageAdded(SessionEvent.Role.ASSISTANT, "reply 2"),
                new SessionEvent.RunFinished(false)), events(store));
    }

    @Test
    void rejectsEnqueueWhileIdleAndDoesNotProcessRejectedInput() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        ScriptedModelClient model = new ScriptedModelClient(List.of(response(
                new StreamChunk.Text("reply"),
                new StreamChunk.Done(StreamChunk.StopReason.STOP))), null);
        RunLoop runLoop = new RunLoop(model, store);

        assertFalse(runLoop.enqueue("idle input"));
        runLoop.run(SESSION_ID, "first");

        assertEquals(1, model.requests.size());
        assertEquals(List.of(
                new SessionEvent.MessageAdded(SessionEvent.Role.USER, "first"),
                new SessionEvent.MessageAdded(SessionEvent.Role.ASSISTANT, "reply"),
                new SessionEvent.RunFinished(false)), events(store));
    }

    @Test
    void cancellationDiscardsQueuedInputAndFinishesAborted() throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<Throwable> runFailure = new AtomicReference<>();
        ModelClient model = request -> {
            requestCount.incrementAndGet();
            modelStarted.countDown();
            await(releaseModel);
            return publisherOf(
                    new StreamChunk.Text("reply"),
                    new StreamChunk.Done(StreamChunk.StopReason.STOP));
        };
        RunLoop runLoop = new RunLoop(model, store);
        Thread runner = new Thread(() -> {
            try {
                runLoop.run(SESSION_ID, "first");
            } catch (Throwable throwable) {
                runFailure.set(throwable);
            }
        });

        runner.start();
        await(modelStarted);
        assertTrue(runLoop.enqueue("queued"));
        runLoop.cancel();
        assertFalse(runLoop.enqueue("after cancel", Inbox.Target.STEP));
        releaseModel.countDown();
        join(runner);

        assertNull(runFailure.get());
        assertEquals(1, requestCount.get());
        assertEquals(List.of(
                new SessionEvent.MessageAdded(SessionEvent.Role.USER, "first"),
                new SessionEvent.RunFinished(true)), events(store));
        assertTrue(runLoop.finalPhase() instanceof Phase.Maintenance);
    }

    @Test
    void givesEachTurnItsOwnMaxStepsBudget() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<RunLoop> loopReference = new AtomicReference<>();
        AtomicBoolean queued = new AtomicBoolean();
        ModelClient model = request -> {
            int call = modelCalls.incrementAndGet();
            if (queued.compareAndSet(false, true)) {
                assertTrue(loopReference.get().enqueue("second"));
            }
            return switch (call) {
                case 1, 3 -> publisherOf(
                        new StreamChunk.ToolCall("call-" + call, "missing", "{}"),
                        new StreamChunk.Done(StreamChunk.StopReason.STOP));
                case 2 -> publisherOf(
                        new StreamChunk.Text("first reply"),
                        new StreamChunk.Done(StreamChunk.StopReason.STOP));
                case 4 -> publisherOf(
                        new StreamChunk.Text("second reply"),
                        new StreamChunk.Done(StreamChunk.StopReason.STOP));
                default -> throw new AssertionError("unexpected model call " + call);
            };
        };
        RunLoop runLoop = new RunLoop(
                model, store, config(2, ToolRegistry.empty()));
        loopReference.set(runLoop);

        runLoop.run(SESSION_ID, "first");

        assertEquals(4, modelCalls.get());
        assertEquals(List.of("first", "second"), userMessages(store).stream()
                .map(SessionEvent.MessageAdded::content)
                .toList());
        assertEquals(List.of("first reply", "second reply"), assistantMessages(store).stream()
                .map(SessionEvent.MessageAdded::content)
                .toList());
        assertEquals(new SessionEvent.RunFinished(false), events(store).getLast());
    }

    @Test
    void abortsEntireRunWhenOneTurnExhaustsMaxStepsAndDiscardsQueuedInput() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<RunLoop> loopReference = new AtomicReference<>();
        ModelClient model = request -> {
            modelCalls.incrementAndGet();
            if (modelCalls.get() == 1) {
                assertTrue(loopReference.get().enqueue("second"));
            }
            return publisherOf(
                    new StreamChunk.ToolCall("call-" + modelCalls.get(), "missing", "{}"),
                    new StreamChunk.Done(StreamChunk.StopReason.STOP));
        };
        RunLoop runLoop = new RunLoop(
                model, store, config(2, ToolRegistry.empty()));
        loopReference.set(runLoop);

        runLoop.run(SESSION_ID, "first");

        assertEquals(2, modelCalls.get());
        assertEquals(List.of("first"), userMessages(store).stream()
                .map(SessionEvent.MessageAdded::content)
                .toList());
        assertEquals(new SessionEvent.RunFinished(true), events(store).getLast());
        assertTrue(runLoop.finalPhase() instanceof Phase.Maintenance);
    }

    @Test
    void processesInputEnqueuedFromAnotherThreadWhileModelIsRunning() throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean();
        AtomicReference<Throwable> enqueueFailure = new AtomicReference<>();
        AtomicInteger requestCount = new AtomicInteger();
        ModelClient model = request -> {
            if (requestCount.incrementAndGet() == 1) {
                modelStarted.countDown();
                await(releaseModel);
            }
            return publisherOf(
                    new StreamChunk.Text("reply " + request.input()),
                    new StreamChunk.Done(StreamChunk.StopReason.STOP));
        };
        RunLoop runLoop = new RunLoop(model, store);
        Thread enqueuer = new Thread(() -> {
            try {
                await(modelStarted);
                accepted.set(runLoop.enqueue("second"));
            } catch (Throwable throwable) {
                enqueueFailure.set(throwable);
            } finally {
                releaseModel.countDown();
            }
        });

        enqueuer.start();
        runLoop.run(SESSION_ID, "first");
        join(enqueuer);

        assertNull(enqueueFailure.get());
        assertTrue(accepted.get());
        assertEquals(2, requestCount.get());
        assertEquals(List.of(
                new SessionEvent.MessageAdded(SessionEvent.Role.USER, "first"),
                new SessionEvent.MessageAdded(SessionEvent.Role.ASSISTANT, "reply first"),
                new SessionEvent.MessageAdded(SessionEvent.Role.USER, "second"),
                new SessionEvent.MessageAdded(SessionEvent.Role.ASSISTANT, "reply second"),
                new SessionEvent.RunFinished(false)), events(store));
    }

    @Test
    void injectsStepInputBeforeTheNextStepWithoutInterruptingToolExecution()
            throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch releaseTool = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean();
        AtomicReference<Throwable> enqueueFailure = new AtomicReference<>();
        AtomicReference<ModelRequest> secondRequest = new AtomicReference<>();
        AtomicInteger requestCount = new AtomicInteger();
        ModelClient model = request -> {
            if (requestCount.incrementAndGet() == 2) {
                secondRequest.set(request);
            }
            return publisherOf(
                    requestCount.get() == 1
                            ? new StreamChunk.ToolCall("call-1", "file", "{}")
                            : new StreamChunk.Text("done"),
                    new StreamChunk.Done(StreamChunk.StopReason.STOP));
        };
        RunLoop runLoop = new RunLoop(
                model,
                store,
                config(
                        blockingFileRegistry(toolStarted, releaseTool),
                        ALLOW_ALL,
                        ApprovalGate.denyAll(),
                        ApprovalGrantStore.none()));
        Thread enqueuer = new Thread(() -> {
            try {
                await(toolStarted);
                accepted.set(runLoop.enqueue("纠正方向", Inbox.Target.STEP));
            } catch (Throwable throwable) {
                enqueueFailure.set(throwable);
            } finally {
                releaseTool.countDown();
            }
        });

        enqueuer.start();
        runLoop.run(SESSION_ID, "first");
        join(enqueuer);

        assertNull(enqueueFailure.get());
        assertTrue(accepted.get());
        assertEquals(2, requestCount.get());
        List<SessionEvent> trace = eventTrace(store);
        assertEquals(6, trace.size());
        assertEquals(
                new SessionEvent.MessageAdded(SessionEvent.Role.USER, "first"),
                trace.get(0));
        assertTrue(trace.get(1) instanceof SessionEvent.ToolCallStarted);
        assertTrue(trace.get(2) instanceof SessionEvent.ToolCallCompleted);
        assertEquals(
                new SessionEvent.MessageAdded(
                        SessionEvent.Role.USER, "纠正方向"),
                trace.get(3));
        assertEquals(
                new SessionEvent.MessageAdded(
                        SessionEvent.Role.ASSISTANT, "done"),
                trace.get(4));
        assertEquals(new SessionEvent.RunFinished(false), trace.get(5));
        assertEquals(
                new ModelMessage.User("纠正方向"),
                secondRequest.get().history().getLast());
    }

    @Test
    void stepInputDoesNotResetTheCurrentTurnStepBudget() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        AtomicReference<RunLoop> loopReference = new AtomicReference<>();
        AtomicBoolean enqueued = new AtomicBoolean();
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient model = request -> {
            modelCalls.incrementAndGet();
            if (enqueued.compareAndSet(false, true)) {
                assertTrue(loopReference.get().enqueue(
                        "纠正方向", Inbox.Target.STEP));
            }
            return publisherOf(
                    new StreamChunk.ToolCall("call-" + modelCalls.get(), "missing", "{}"),
                    new StreamChunk.Done(StreamChunk.StopReason.STOP));
        };
        RunLoop runLoop = new RunLoop(
                model, store, config(2, ToolRegistry.empty()));
        loopReference.set(runLoop);

        runLoop.run(SESSION_ID, "first");

        assertEquals(2, modelCalls.get());
        assertEquals(
                List.of("first", "纠正方向"),
                userMessages(store).stream()
                        .map(SessionEvent.MessageAdded::content)
                        .toList());
        assertEquals(new SessionEvent.RunFinished(true), events(store).getLast());
    }

    @Test
    void promotesUnconsumedStepInputToTheNextTurn() throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean();
        AtomicReference<Throwable> enqueueFailure = new AtomicReference<>();
        AtomicInteger requestCount = new AtomicInteger();
        ModelClient model = request -> {
            if (requestCount.incrementAndGet() == 1) {
                modelStarted.countDown();
                await(releaseModel);
            }
            return publisherOf(
                    new StreamChunk.Text("reply " + request.input()),
                    new StreamChunk.Done(StreamChunk.StopReason.STOP));
        };
        RunLoop runLoop = new RunLoop(model, store);
        Thread enqueuer = new Thread(() -> {
            try {
                await(modelStarted);
                accepted.set(runLoop.enqueue("late steer", Inbox.Target.STEP));
            } catch (Throwable throwable) {
                enqueueFailure.set(throwable);
            } finally {
                releaseModel.countDown();
            }
        });

        enqueuer.start();
        runLoop.run(SESSION_ID, "first");
        join(enqueuer);

        assertNull(enqueueFailure.get());
        assertTrue(accepted.get());
        assertEquals(2, requestCount.get());
        assertEquals(
                List.of("first", "late steer"),
                userMessages(store).stream()
                        .map(SessionEvent.MessageAdded::content)
                        .toList());
        assertEquals(List.of(
                new SessionEvent.MessageAdded(SessionEvent.Role.USER, "first"),
                new SessionEvent.MessageAdded(
                        SessionEvent.Role.ASSISTANT, "reply first"),
                new SessionEvent.MessageAdded(
                        SessionEvent.Role.USER, "late steer"),
                new SessionEvent.MessageAdded(
                        SessionEvent.Role.ASSISTANT, "reply late steer"),
                new SessionEvent.RunFinished(false)), events(store));
    }

    @Test
    void rejectsEnqueueWhenInboxIsFull() throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        AtomicInteger acceptedCount = new AtomicInteger();
        AtomicBoolean overflowRejected = new AtomicBoolean();
        AtomicReference<Throwable> enqueueFailure = new AtomicReference<>();
        AtomicInteger requestCount = new AtomicInteger();
        ModelClient model = request -> {
            if (requestCount.incrementAndGet() == 1) {
                modelStarted.countDown();
                await(releaseModel);
            }
            return publisherOf(
                    requestCount.get() == 1
                            ? new StreamChunk.ToolCall("call-1", "missing", "{}")
                            : new StreamChunk.Text("done"),
                    new StreamChunk.Done(StreamChunk.StopReason.STOP));
        };
        RunLoop runLoop = new RunLoop(model, store);
        Thread enqueuer = new Thread(() -> {
            try {
                await(modelStarted);
                for (int index = 0; index < Inbox.DEFAULT_CAPACITY; index++) {
                    if (runLoop.enqueue("step " + index, Inbox.Target.STEP)) {
                        acceptedCount.incrementAndGet();
                    }
                }
                overflowRejected.set(
                        !runLoop.enqueue("overflow", Inbox.Target.STEP));
            } catch (Throwable throwable) {
                enqueueFailure.set(throwable);
            } finally {
                releaseModel.countDown();
            }
        });

        enqueuer.start();
        runLoop.run(SESSION_ID, "first");
        join(enqueuer);

        assertNull(enqueueFailure.get());
        assertEquals(Inbox.DEFAULT_CAPACITY, acceptedCount.get());
        assertTrue(overflowRejected.get());
        assertEquals(2, requestCount.get());
        assertEquals(
                Inbox.DEFAULT_CAPACITY + 1,
                userMessages(store).size());
        assertEquals(new SessionEvent.RunFinished(false), events(store).getLast());
    }

    @Test
    void forwardsEveryTextDeltaAndPersistsOneAssistantMessage() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        ScriptedModelClient model = new ScriptedModelClient(List.of(response(
                new StreamChunk.Text("a"),
                new StreamChunk.Text("b"),
                new StreamChunk.Done(StreamChunk.StopReason.STOP))), null);
        List<String> sinkEvents = new ArrayList<>();
        StreamSink streamSink = streamSink(
                sinkEvents::add,
                call -> sinkEvents.add("tool:" + call.callId()),
                reason -> sinkEvents.add("done:" + reason));

        new RunLoop(
                        model,
                        store,
                        new RunLoopConfig(
                                RunLoop.DEFAULT_MAX_STEPS,
                                streamSink,
                                ToolRegistry.empty(),
                                RuleBasedApprovalPolicy.defaults(),
                                ApprovalGate.denyAll(),
                                ApprovalGrantStore.none()))
                .run(SESSION_ID, "你好");

        assertEquals(List.of("a", "b", "done:STOP"), sinkEvents);
        assertEquals(List.of(new SessionEvent.MessageAdded(
                SessionEvent.Role.ASSISTANT, "ab")), assistantMessages(store));
    }

    @Test
    void executesToolAndFeedsItsResultBackToNextStep() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        ScriptedModelClient model = new ScriptedModelClient(List.of(
                response(
                        new StreamChunk.Text("checking"),
                        new StreamChunk.ToolCall("call-1", "file", "read x"),
                        new StreamChunk.Done(StreamChunk.StopReason.STOP)),
                response(
                        new StreamChunk.Text("done"),
                        new StreamChunk.Done(StreamChunk.StopReason.STOP))), null);

        new RunLoop(
                        model,
                        store,
                        config(
                                fileRegistry(),
                                ALLOW_ALL,
                                ApprovalGate.denyAll(),
                                ApprovalGrantStore.none()))
                .run(SESSION_ID, "读取 x");

        assertTrue(events(store).contains(
                new SessionEvent.ToolCallStarted("call-1", "file", "read x")));
        SessionEvent.ToolCallCompleted completed = events(store).stream()
                .filter(SessionEvent.ToolCallCompleted.class::isInstance)
                .map(SessionEvent.ToolCallCompleted.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("call-1", completed.callId());
        assertEquals("ok", completed.result().content());
        assertFalse(completed.result().isError());
        assertTrue(completed.result().durationMs() >= 0L);
        assertEquals(List.of(
                new ModelMessage.User("读取 x"),
                new ModelMessage.Assistant(
                        "checking",
                        List.of(new StreamChunk.ToolCall("call-1", "file", "read x"))),
                new ModelMessage.Tool("call-1", "ok", false)),
                model.requests.get(1).history());
        assertEquals(new SessionEvent.RunFinished(false), events(store).getLast());
    }

    @Test
    void doesNotPersistEmptyAssistantMessageForToolOnlyResponse() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        ScriptedModelClient model = new ScriptedModelClient(List.of(
                response(
                        new StreamChunk.ToolCall("call-1", "file", "read x"),
                        new StreamChunk.Done(StreamChunk.StopReason.STOP)),
                response(
                        new StreamChunk.Text("done"),
                        new StreamChunk.Done(StreamChunk.StopReason.STOP))), null);

        new RunLoop(
                        model,
                        store,
                        config(
                                fileRegistry(),
                                ALLOW_ALL,
                                ApprovalGate.denyAll(),
                                ApprovalGrantStore.none()))
                .run(SESSION_ID, "读取 x");

        assertEquals(List.of(
                new SessionEvent.MessageAdded(SessionEvent.Role.ASSISTANT, "done")),
                assistantMessages(store));
        assertEquals(List.of(
                new ModelMessage.User("读取 x"),
                new ModelMessage.Assistant(
                        "",
                        List.of(new StreamChunk.ToolCall("call-1", "file", "read x"))),
                new ModelMessage.Tool("call-1", "ok", false)),
                model.requests.get(1).history());
    }

    @Test
    void deniesUnapprovedToolByDefaultWithoutUi() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        ScriptedModelClient model = new ScriptedModelClient(List.of(
                response(
                        new StreamChunk.ToolCall("call-1", "file", "read x"),
                        new StreamChunk.Done(StreamChunk.StopReason.STOP)),
                response(
                        new StreamChunk.Text("denied"),
                        new StreamChunk.Done(StreamChunk.StopReason.STOP))), null);

        new RunLoop(model, store, config(fileRegistry())).run(SESSION_ID, "读取 x");

        SessionEvent.ToolCallCompleted completed = events(store).stream()
                .filter(SessionEvent.ToolCallCompleted.class::isInstance)
                .map(SessionEvent.ToolCallCompleted.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(
                new ToolResult("denied by unattended", true, 0L),
                completed.result());
        assertTrue(events(store).contains(
                new SessionEvent.ApprovalResolved("call-1", false, "unattended")));
    }

    @Test
    void clarifyFinishesWithoutCallingModel() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        ScriptedModelClient model = new ScriptedModelClient(List.of(), null);

        new RunLoop(model, store).run(SESSION_ID, " ");

        assertTrue(model.requests.isEmpty());
        assertEquals(List.of(
                new SessionEvent.MessageAdded(SessionEvent.Role.USER, " "),
                new SessionEvent.MessageAdded(
                        SessionEvent.Role.ASSISTANT, "请补充更多信息。"),
                new SessionEvent.RunFinished(false)), events(store));
    }

    @Test
    void abortsAfterExactlyMaxStepsWhenModelKeepsCallingTools() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        List<StreamChunk> repeatingToolResponse = response(
                new StreamChunk.ToolCall("call-1", "file", "read x"),
                new StreamChunk.Done(StreamChunk.StopReason.STOP));
        ScriptedModelClient model = new ScriptedModelClient(List.of(), repeatingToolResponse);
        RunLoop runLoop = new RunLoop(model, store, config(3, ToolRegistry.empty()));

        runLoop.run(SESSION_ID, "读取 x");

        assertEquals(3, model.requests.size());
        assertEquals(new SessionEvent.RunFinished(true), events(store).getLast());
        assertTrue(runLoop.finalPhase() instanceof Phase.Maintenance);
    }

    @Test
    void marksRunAbortedWhenCancelledDuringStep() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        AtomicReference<RunLoop> loopReference = new AtomicReference<>();
        ModelClient cancellingModel = request -> {
            loopReference.get().cancel();
            return publisherOf(
                    new StreamChunk.Text("started"),
                    new StreamChunk.Done(StreamChunk.StopReason.STOP));
        };
        RunLoop runLoop = new RunLoop(cancellingModel, store);
        loopReference.set(runLoop);

        runLoop.run(SESSION_ID, "你好");

        assertEquals(new SessionEvent.RunFinished(true), events(store).getLast());
        assertTrue(runLoop.finalPhase() instanceof Phase.Maintenance);
    }

    @Test
    void preservesCancellationRequestedBeforeRunAndDoesNotCallModel() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        ScriptedModelClient model = new ScriptedModelClient(List.of(), null);
        RunLoop runLoop = new RunLoop(model, store);
        runLoop.cancel();

        runLoop.run(SESSION_ID, "你好");

        assertTrue(model.requests.isEmpty());
        assertEquals(new SessionEvent.RunFinished(true), events(store).getLast());
        assertTrue(runLoop.finalPhase() instanceof Phase.Maintenance);
    }

    @Test
    void convertsModelErrorIntoAbortedRunWithoutThrowing() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        ScriptedModelClient model = new ScriptedModelClient(List.of(response(
                new StreamChunk.Error("boom"))), null);

        new RunLoop(model, store).run(SESSION_ID, "你好");

        assertEquals(new SessionEvent.RunFinished(true), events(store).getLast());
    }

    @Test
    void convertsPublisherFailureIntoAbortedRunWithoutThrowing() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        ModelClient failingModel = request -> subscriber ->
                subscriber.onSubscribe(new NoOpSubscription() {
                    @Override
                    public void request(long count) {
                        subscriber.onError(new IllegalStateException("publisher failed"));
                    }
                });

        new RunLoop(failingModel, store).run(SESSION_ID, "你好");

        assertEquals(new SessionEvent.RunFinished(true), events(store).getLast());
    }

    @Test
    void continuesRunWhenModelStopsForLength() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        ScriptedModelClient model = new ScriptedModelClient(List.of(
                response(
                        new StreamChunk.Text("partial"),
                        new StreamChunk.Done(StreamChunk.StopReason.LENGTH)),
                response(
                        new StreamChunk.Text(" done"),
                        new StreamChunk.Done(StreamChunk.StopReason.STOP))), null);

        new RunLoop(model, store).run(SESSION_ID, "你好");

        assertFalse(events(store).getLast() instanceof SessionEvent.RunFinished finished
                && finished.aborted());
        assertEquals(2, model.requests.size());
        assertEquals(2, model.requests.get(1).history().size());
    }

    @Test
    void repeatedLengthStopsAtMaxSteps() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        List<StreamChunk> lengthResponse = response(
                new StreamChunk.Text("partial"),
                new StreamChunk.Done(StreamChunk.StopReason.LENGTH));
        ScriptedModelClient model =
                new ScriptedModelClient(List.of(), lengthResponse);
        RunLoop runLoop = new RunLoop(model, store, config(3, ToolRegistry.empty()));

        runLoop.run(SESSION_ID, "你好");

        assertEquals(3, model.requests.size());
        assertEquals(new SessionEvent.RunFinished(true), events(store).getLast());
        assertTrue(runLoop.finalPhase() instanceof Phase.Maintenance);
    }

    @Test
    void sendsRegisteredToolSpecsToModel() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        ScriptedModelClient model = new ScriptedModelClient(List.of(response(
                new StreamChunk.Done(StreamChunk.StopReason.STOP))), null);
        ToolRegistry registry = new ToolRegistry(List.of(
                specifiedTool("zeta", "last"),
                specifiedTool("alpha", "first")));

        new RunLoop(model, store, config(registry)).run(SESSION_ID, "hello");

        assertEquals(List.of(
                new ToolSpec("alpha", "first", "{\"type\":\"object\"}"),
                new ToolSpec("zeta", "last", "{\"type\":\"object\"}")),
                model.requests.getFirst().tools());
    }

    private static StreamSink streamSink(
            java.util.function.Consumer<String> onDelta,
            java.util.function.Consumer<StreamChunk.ToolCall> onToolCall,
            java.util.function.Consumer<StreamChunk.StopReason> onDone) {
        return new StreamSink() {
            @Override
            public void onDelta(String delta) {
                onDelta.accept(delta);
            }

            @Override
            public void onToolCall(StreamChunk.ToolCall call) {
                onToolCall.accept(call);
            }

            @Override
            public void onDone(StreamChunk.StopReason reason) {
                onDone.accept(reason);
            }
        };
    }

    private static RunLoopConfig config(ToolRegistry toolRegistry) {
        RunLoopConfig defaults = RunLoopConfig.defaults();
        return new RunLoopConfig(
                defaults.maxSteps(),
                defaults.streamSink(),
                toolRegistry,
                defaults.approvalPolicy(),
                defaults.approvalGate(),
                defaults.approvalGrants());
    }

    private static RunLoopConfig config(
            ToolRegistry toolRegistry,
            ApprovalPolicy approvalPolicy,
            ApprovalGate approvalGate,
            ApprovalGrantStore approvalGrants) {
        RunLoopConfig defaults = RunLoopConfig.defaults();
        return new RunLoopConfig(
                defaults.maxSteps(),
                defaults.streamSink(),
                toolRegistry,
                approvalPolicy,
                approvalGate,
                approvalGrants);
    }

    private static RunLoopConfig config(int maxSteps, ToolRegistry toolRegistry) {
        RunLoopConfig defaults = RunLoopConfig.defaults();
        return new RunLoopConfig(
                maxSteps,
                defaults.streamSink(),
                toolRegistry,
                defaults.approvalPolicy(),
                defaults.approvalGate(),
                defaults.approvalGrants());
    }

    private static List<StreamChunk> response(StreamChunk... chunks) {
        return List.of(chunks);
    }

    private static List<SessionEvent> events(InMemorySessionEventStore store) {
        return store.from(SESSION_ID, 0).stream().map(StoredEvent::event).toList();
    }

    private static List<SessionEvent> eventTrace(InMemorySessionEventStore store) {
        return events(store).stream()
                .filter(event -> event instanceof SessionEvent.MessageAdded
                        || event instanceof SessionEvent.ToolCallStarted
                        || event instanceof SessionEvent.ToolCallCompleted
                        || event instanceof SessionEvent.RunFinished)
                .toList();
    }

    private static List<SessionEvent.MessageAdded> assistantMessages(
            InMemorySessionEventStore store) {
        return events(store).stream()
                .filter(SessionEvent.MessageAdded.class::isInstance)
                .map(SessionEvent.MessageAdded.class::cast)
                .filter(message -> message.role() == SessionEvent.Role.ASSISTANT)
                .toList();
    }

    private static List<SessionEvent.MessageAdded> userMessages(
            InMemorySessionEventStore store) {
        return events(store).stream()
                .filter(SessionEvent.MessageAdded.class::isInstance)
                .map(SessionEvent.MessageAdded.class::cast)
                .filter(message -> message.role() == SessionEvent.Role.USER)
                .toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        assertFalse(thread.isAlive(), "thread did not finish");
    }

    private static ToolRegistry fileRegistry() {
        return new ToolRegistry(List.of(new Tool() {
            @Override
            public String name() {
                return "file";
            }

            @Override
            public ToolResult execute(String argumentsJson) {
                return new ToolResult("ok", false, 0L);
            }
        }));
    }

    private static ToolRegistry blockingFileRegistry(
            CountDownLatch started,
            CountDownLatch release) {
        return new ToolRegistry(List.of(new Tool() {
            @Override
            public String name() {
                return "file";
            }

            @Override
            public ToolResult execute(String argumentsJson) {
                started.countDown();
                await(release);
                return new ToolResult("ok", false, 0L);
            }
        }));
    }

    private static Tool specifiedTool(String name, String description) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public String parametersSchema() {
                return "{\"type\":\"object\"}";
            }

            @Override
            public ToolResult execute(String argumentsJson) {
                return new ToolResult("ok", false, 0L);
            }
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

    private static class NoOpSubscription implements Flow.Subscription {

        @Override
        public void request(long count) {}

        @Override
        public void cancel() {}
    }

    private static final class ScriptedModelClient implements ModelClient {

        private final List<List<StreamChunk>> responses;
        private final List<StreamChunk> repeatingResponse;
        private final List<ModelRequest> requests = new ArrayList<>();

        private ScriptedModelClient(
                List<List<StreamChunk>> responses,
                List<StreamChunk> repeatingResponse) {
            this.responses = responses;
            this.repeatingResponse = repeatingResponse;
        }

        @Override
        public Flow.Publisher<StreamChunk> stream(ModelRequest request) {
            requests.add(request);
            List<StreamChunk> chunks = requests.size() <= responses.size()
                    ? responses.get(requests.size() - 1)
                    : repeatingResponse;
            return publisherOf(chunks.toArray(StreamChunk[]::new));
        }
    }
}
