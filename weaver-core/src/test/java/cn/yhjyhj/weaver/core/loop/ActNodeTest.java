package cn.yhjyhj.weaver.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.loop.context.RunRequest;
import cn.yhjyhj.weaver.core.loop.node.ActNode;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Tool;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ActNodeTest {

    @Test
    void reportsUnknownToolAsErrorAndStillPairsEvents() throws Exception {
        List<SessionEvent> emitted = execute(
                ToolRegistry.empty(),
                new StreamChunk.ToolCall("call-1", "missing", "{}"));

        assertEquals(2, emitted.size());
        assertEquals(
                new SessionEvent.ToolCallStarted("call-1", "missing", "{}"),
                emitted.get(0));
        SessionEvent.ToolCallCompleted completed =
                assertInstanceOf(SessionEvent.ToolCallCompleted.class, emitted.get(1));
        assertEquals("call-1", completed.callId());
        assertEquals(new ToolResult("unknown tool: missing", true, 0L), completed.result());
    }

    @Test
    void runsSeriallyWhenAnyToolIsNotParallelizable() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        Tool serial = tool("serial", false, argumentsJson -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            pause(50L);
            active.decrementAndGet();
            return new ToolResult("serial", false, 0L);
        });
        Tool parallel = tool("parallel", true, argumentsJson -> {
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            pause(50L);
            active.decrementAndGet();
            return new ToolResult("parallel", false, 0L);
        });

        execute(
                new ToolRegistry(List.of(serial, parallel)),
                new StreamChunk.ToolCall("call-1", "serial", "{}"),
                new StreamChunk.ToolCall("call-2", "parallel", "{}"));

        assertEquals(1, maxActive.get());
    }

    @Test
    void runsAllParallelizableToolsConcurrently() throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2);
        Tool left = tool("left", true, argumentsJson -> {
            bothEntered.countDown();
            await(bothEntered);
            return new ToolResult("left", false, 0L);
        });
        Tool right = tool("right", true, argumentsJson -> {
            bothEntered.countDown();
            await(bothEntered);
            return new ToolResult("right", false, 0L);
        });

        List<SessionEvent> emitted = execute(
                new ToolRegistry(List.of(left, right)),
                new StreamChunk.ToolCall("call-1", "left", "{}"),
                new StreamChunk.ToolCall("call-2", "right", "{}"));

        List<ToolResult> results = completedResults(emitted);
        assertEquals("left", results.get(0).content());
        assertEquals("right", results.get(1).content());
    }

    @Test
    void emitsAllStartsBeforeCompletionsInOriginalOrder() throws Exception {
        CountDownLatch secondCompleted = new CountDownLatch(1);
        Tool first = tool("first", true, argumentsJson -> {
            await(secondCompleted);
            return new ToolResult("first", false, 0L);
        });
        Tool second = tool("second", true, argumentsJson -> {
            secondCompleted.countDown();
            return new ToolResult("second", false, 0L);
        });

        List<SessionEvent> emitted = execute(
                new ToolRegistry(List.of(first, second)),
                new StreamChunk.ToolCall("call-1", "first", "{\"n\":1}"),
                new StreamChunk.ToolCall("call-2", "second", "{\"n\":2}"));

        assertEquals(4, emitted.size());
        assertEquals(
                new SessionEvent.ToolCallStarted("call-1", "first", "{\"n\":1}"),
                emitted.get(0));
        assertEquals(
                new SessionEvent.ToolCallStarted("call-2", "second", "{\"n\":2}"),
                emitted.get(1));
        assertInstanceOf(SessionEvent.ToolCallCompleted.class, emitted.get(2));
        assertInstanceOf(SessionEvent.ToolCallCompleted.class, emitted.get(3));
    }

    @Test
    void pairsOutOfOrderParallelResultsWithOriginalCallIds() throws Exception {
        CountDownLatch secondCompleted = new CountDownLatch(1);
        Tool slow = tool("slow", true, argumentsJson -> {
            await(secondCompleted);
            return new ToolResult("slow-result", false, 0L);
        });
        Tool fast = tool("fast", true, argumentsJson -> {
            secondCompleted.countDown();
            return new ToolResult("fast-result", false, 0L);
        });

        List<SessionEvent> emitted = execute(
                new ToolRegistry(List.of(slow, fast)),
                new StreamChunk.ToolCall("call-1", "slow", "{}"),
                new StreamChunk.ToolCall("call-2", "fast", "{}"));

        List<SessionEvent.ToolCallCompleted> completed = emitted.stream()
                .filter(SessionEvent.ToolCallCompleted.class::isInstance)
                .map(SessionEvent.ToolCallCompleted.class::cast)
                .toList();
        assertEquals(List.of("call-1", "call-2"), completed.stream()
                .map(SessionEvent.ToolCallCompleted::callId)
                .toList());
        assertEquals(List.of("slow-result", "fast-result"), completed.stream()
                .map(event -> event.result().content())
                .toList());
    }

    @Test
    void recordsElapsedMillisecondsAndClearsPendingCalls() throws Exception {
        Tool timed = tool("timed", true, argumentsJson -> {
            pause(25L);
            return new ToolResult("done", false, 0L);
        });
        List<SessionEvent> emitted = new ArrayList<>();
        RunContext context = new RunContext("session", emitted::add);
        context.setPendingToolCalls(List.of(
                new StreamChunk.ToolCall("call-1", "timed", "{}")));
        ActNode node = new ActNode(new ToolRegistry(List.of(timed)), (request, ignored) -> null);

        assertNull(node.apply(new RunRequest("session", "input", 0), context));

        assertTrue(context.getPendingToolCalls().isEmpty());
        assertTrue(context.isToolExecuted());
        ToolResult result = completedResults(emitted).getFirst();
        assertFalse(result.isError());
        assertTrue(result.durationMs() >= 25L);
    }

    private static List<SessionEvent> execute(
            ToolRegistry registry, StreamChunk.ToolCall... calls) throws Exception {
        List<SessionEvent> emitted = new ArrayList<>();
        RunContext context = new RunContext("session", emitted::add);
        context.setPendingToolCalls(List.of(calls));
        ActNode node = new ActNode(registry, (request, ignored) -> null);

        assertNull(node.apply(new RunRequest("session", "input", 0), context));
        assertTrue(context.getPendingToolCalls().isEmpty());
        assertTrue(context.isToolExecuted());
        return emitted;
    }

    private static List<ToolResult> completedResults(List<SessionEvent> emitted) {
        return emitted.stream()
                .filter(SessionEvent.ToolCallCompleted.class::isInstance)
                .map(SessionEvent.ToolCallCompleted.class::cast)
                .map(SessionEvent.ToolCallCompleted::result)
                .toList();
    }

    private static Tool tool(
            String name,
            boolean parallelizable,
            Function<String, ToolResult> behavior) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolResult execute(String argumentsJson) {
                return behavior.apply(argumentsJson);
            }

            @Override
            public boolean parallelizable() {
                return parallelizable;
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("tools did not run concurrently");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tool execution interrupted", exception);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("tool execution interrupted", exception);
        }
    }
}
