package cn.yhjyhj.weaver.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.events.InMemorySessionEventStore;
import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.loop.chain.StrategyHandler;
import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.loop.context.RunRequest;
import cn.yhjyhj.weaver.core.loop.context.StepResult;
import cn.yhjyhj.weaver.core.loop.node.ContextNode;
import cn.yhjyhj.weaver.core.model.ModelMessage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ContextNodeTest {

    @Test
    void routesFirstStepToIntentAndLaterStepsToReasonAfterReplay() throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        store.append(
                "session", new SessionEvent.MessageAdded(SessionEvent.Role.USER, "earlier"));
        AtomicInteger intentCalls = new AtomicInteger();
        AtomicInteger reasonCalls = new AtomicInteger();
        StrategyHandler<RunRequest, RunContext, StepResult> intent =
                (request, context) -> {
                    intentCalls.incrementAndGet();
                    return new StepResult(true, false, "intent");
                };
        StrategyHandler<RunRequest, RunContext, StepResult> reason =
                (request, context) -> {
                    reasonCalls.incrementAndGet();
                    return new StepResult(true, false, "reason");
                };
        ContextNode node = new ContextNode(store, intent, reason);

        StepResult first = node.apply(
                new RunRequest("session", "input", 0),
                new RunContext("session", event -> {}));
        StepResult later = node.apply(
                new RunRequest("session", "input", 1),
                new RunContext("session", event -> {}));

        assertEquals("intent", first.text());
        assertEquals("reason", later.text());
        assertEquals(1, intentCalls.get());
        assertEquals(1, reasonCalls.get());
        assertTrue(later.finished());
    }

    @Test
    void replaysHistoryBeforeRoutingForEveryStep() throws Exception {
        InMemorySessionEventStore store = new InMemorySessionEventStore();
        store.append(
                "session", new SessionEvent.MessageAdded(SessionEvent.Role.USER, "earlier"));
        ContextNode node = new ContextNode(
                store,
                (request, context) -> new StepResult(
                        true,
                        false,
                        ((ModelMessage.User) context.getHistory().getFirst()).content()),
                (request, context) -> null);
        RunContext context = new RunContext("session", event -> {});

        StepResult result = node.apply(new RunRequest("session", "input", 0), context);

        assertEquals("earlier", result.text());
        assertEquals(new ModelMessage.User("earlier"), context.getHistory().getFirst());
    }
}
