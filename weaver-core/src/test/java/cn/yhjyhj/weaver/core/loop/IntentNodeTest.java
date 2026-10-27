package cn.yhjyhj.weaver.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.loop.chain.StrategyHandler;
import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.loop.context.RunRequest;
import cn.yhjyhj.weaver.core.loop.context.StepResult;
import cn.yhjyhj.weaver.core.loop.intent.Intent;
import cn.yhjyhj.weaver.core.loop.intent.IntentClassifier;
import cn.yhjyhj.weaver.core.loop.node.IntentNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntentNodeTest {

    @Test
    void classifiesOnlyTheFirstStepAndRoutesToReason() throws Exception {
        List<String> classifications = new ArrayList<>();
        IntentClassifier classifier = input -> {
            classifications.add(input);
            return Intent.CHAT;
        };
        StrategyHandler<RunRequest, RunContext, StepResult> reason =
                (request, context) -> null;
        IntentNode node = new IntentNode(classifier, reason);
        RunContext context = context();

        assertNull(node.apply(new RunRequest("session", "你好", 0), context));
        assertSame(reason, node.getNext(new RunRequest("session", "你好", 0), context));
        assertEquals(Intent.CHAT, context.getIntent());

        assertNull(node.apply(new RunRequest("session", "你好", 1), context));
        assertEquals(List.of("你好"), classifications);
    }

    @Test
    void clarifyEmitsPromptAndStopsTheStep() throws Exception {
        List<SessionEvent> emitted = new ArrayList<>();
        StrategyHandler<RunRequest, RunContext, StepResult> reason =
                (request, context) -> {
                    throw new AssertionError("reason must not run");
                };
        IntentNode node = new IntentNode(input -> Intent.CLARIFY, reason);
        RunContext context = new RunContext("session", emitted::add);

        StepResult result = node.apply(new RunRequest("session", "", 0), context);

        assertEquals(new StepResult(true, false, "请补充更多信息。"), result);
        assertEquals(List.of(new SessionEvent.MessageAdded(
                SessionEvent.Role.ASSISTANT, "请补充更多信息。")), emitted);
        assertEquals(Intent.CLARIFY, context.getIntent());
    }

    private static RunContext context() {
        return new RunContext("session", event -> {});
    }
}
