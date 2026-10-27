package cn.yhjyhj.weaver.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.events.InMemorySessionEventStore;
import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.loop.context.RunRequest;
import cn.yhjyhj.weaver.core.loop.context.StepResult;
import cn.yhjyhj.weaver.core.loop.node.ContextNode;
import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.model.ModelRequest;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import org.junit.jupiter.api.Test;

class RunChainFactoryTest {

    @Test
    void assemblesFiveNodeChainForPlainResponse() throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        store.append("session", new SessionEvent.MessageAdded(SessionEvent.Role.USER, "earlier"));
        ScriptedModelClient model = new ScriptedModelClient(List.of(List.of(
                new StreamChunk.Text("answer"),
                new StreamChunk.Done(StreamChunk.StopReason.STOP))));
        RunContext context = new RunContext("session", event -> store.append("session", event));

        var chain = RunChainFactory.assemble(store, model);
        StepResult result = chain.apply(new RunRequest("session", "你好", 0), context);

        assertInstanceOf(ContextNode.class, chain);
        assertTrue(result.finished());
        assertEquals(List.of(
                new cn.yhjyhj.weaver.core.events.SessionSnapshot.Message(
                        SessionEvent.Role.USER, "earlier")),
                model.requests.getFirst().history());
    }

    private static final class ScriptedModelClient implements ModelClient {

        private final List<List<StreamChunk>> responses;
        private final List<ModelRequest> requests = new ArrayList<>();

        private ScriptedModelClient(List<List<StreamChunk>> responses) {
            this.responses = responses;
        }

        @Override
        public Flow.Publisher<StreamChunk> stream(ModelRequest request) {
            requests.add(request);
            List<StreamChunk> chunks = responses.get(requests.size() - 1);
            return subscriber -> {
                SubmissionPublisher<StreamChunk> publisher = new SubmissionPublisher<>();
                publisher.subscribe(subscriber);
                chunks.forEach(publisher::submit);
                publisher.close();
            };
        }
    }
}
