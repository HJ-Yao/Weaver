package cn.yhjyhj.weaver.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.loop.context.RunRequest;
import cn.yhjyhj.weaver.core.loop.context.StepResult;
import cn.yhjyhj.weaver.core.loop.node.ObserveNode;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import org.junit.jupiter.api.Test;

class ObserveNodeTest {

    private final ObserveNode node = new ObserveNode();

    @Test
    void abortsWhenModelFailed() throws Exception {
        RunContext context = context();
        context.setModelFailed(true);
        context.setResponseText("partial");

        assertEquals(new StepResult(false, true, "partial"), apply(context));
    }

    @Test
    void finishesAtLengthUntilContinuationIsImplemented() throws Exception {
        RunContext context = context();
        context.setModelDone(true);
        context.setStopReason(StreamChunk.StopReason.LENGTH);
        context.setResponseText("partial");

        assertEquals(new StepResult(true, false, "partial"), apply(context));
    }

    @Test
    void finishesWhenModelDoneWithoutTools() throws Exception {
        RunContext context = context();
        context.setModelDone(true);
        context.setStopReason(StreamChunk.StopReason.STOP);
        context.setResponseText("done");

        assertEquals(new StepResult(true, false, "done"), apply(context));
    }

    @Test
    void continuesAfterToolExecution() throws Exception {
        RunContext context = context();
        context.setModelDone(true);
        context.setStopReason(StreamChunk.StopReason.STOP);
        context.setToolExecuted(true);
        context.setResponseText("checking");

        StepResult result = apply(context);

        assertFalse(result.finished());
        assertFalse(result.aborted());
        assertTrue(result.text().equals("checking"));
    }

    private StepResult apply(RunContext context) throws Exception {
        return node.apply(new RunRequest("session", "input", 0), context);
    }

    private static RunContext context() {
        return new RunContext("session", event -> {});
    }
}
