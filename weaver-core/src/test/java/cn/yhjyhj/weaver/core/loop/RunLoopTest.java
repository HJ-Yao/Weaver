package cn.yhjyhj.weaver.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.events.InMemorySessionEventStore;
import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.SessionSnapshot;
import cn.yhjyhj.weaver.core.events.StoredEvent;
import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.model.ModelRequest;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.model.StreamSink;
import cn.yhjyhj.weaver.core.phase.Phase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RunLoopTest {

    private static final String SESSION_ID = "session";

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

        new RunLoop(model, store, RunLoop.DEFAULT_MAX_STEPS, streamSink)
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

        new RunLoop(model, store).run(SESSION_ID, "读取 x");

        assertTrue(events(store).contains(
                new SessionEvent.ToolCallStarted("call-1", "file", "read x")));
        assertTrue(events(store).contains(new SessionEvent.ToolCallCompleted(
                "call-1", new ToolResult("ok", false, 0L))));
        assertEquals(List.of(
                new SessionSnapshot.Message(SessionEvent.Role.USER, "读取 x"),
                new SessionSnapshot.Message(SessionEvent.Role.ASSISTANT, "checking"),
                new SessionSnapshot.Message(SessionEvent.Role.TOOL, "ok")),
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

        new RunLoop(model, store).run(SESSION_ID, "读取 x");

        assertEquals(List.of(
                new SessionEvent.MessageAdded(SessionEvent.Role.ASSISTANT, "done")),
                assistantMessages(store));
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
        RunLoop runLoop = new RunLoop(model, store, 3);

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
    void finishesRunWhenModelStopsForLength() {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        ModelClient model = request -> publisherOf(
                new StreamChunk.Text("partial"),
                new StreamChunk.Done(StreamChunk.StopReason.LENGTH));

        new RunLoop(model, store).run(SESSION_ID, "你好");

        assertFalse(events(store).getLast() instanceof SessionEvent.RunFinished finished
                && finished.aborted());
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

    private static List<StreamChunk> response(StreamChunk... chunks) {
        return List.of(chunks);
    }

    private static List<SessionEvent> events(InMemorySessionEventStore store) {
        return store.from(SESSION_ID, 0).stream().map(StoredEvent::event).toList();
    }

    private static List<SessionEvent.MessageAdded> assistantMessages(
            InMemorySessionEventStore store) {
        return events(store).stream()
                .filter(SessionEvent.MessageAdded.class::isInstance)
                .map(SessionEvent.MessageAdded.class::cast)
                .filter(message -> message.role() == SessionEvent.Role.ASSISTANT)
                .toList();
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
