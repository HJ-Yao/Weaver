package cn.yhjyhj.weaver.core.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.approval.ApprovalDecision;
import cn.yhjyhj.weaver.core.approval.ApprovalGate;
import cn.yhjyhj.weaver.core.approval.ApprovalPolicy;
import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.loop.context.RunRequest;
import cn.yhjyhj.weaver.core.loop.node.ActNode;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import cn.yhjyhj.weaver.core.tool.Tool;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ActNodeTest {

    private static final ApprovalPolicy ALLOW_ALL =
            (call, reversibility) -> ApprovalDecision.ALLOW; // 旧执行测试显式绕过审批

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
    void denyDoesNotRequestUserApprovalAndStillPairsToolEvents() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger gateCalls = new AtomicInteger();
        Tool dangerous = tool("dangerous", true, argumentsJson -> {
            executions.incrementAndGet();
            return new ToolResult("executed", false, 0L);
        });

        List<SessionEvent> emitted = execute(
                new ToolRegistry(List.of(dangerous)),
                (call, reversibility) -> ApprovalDecision.DENY,
                (call, reversibility) -> {
                    gateCalls.incrementAndGet();
                    return true;
                },
                new StreamChunk.ToolCall("call-1", "dangerous", "{}"));

        assertEquals(0, gateCalls.get());
        assertEquals(0, executions.get());
        assertEquals(
                List.of(
                        new SessionEvent.ApprovalRequested(
                                "call-1", "dangerous", Reversibility.IRREVERSIBLE),
                        new SessionEvent.ApprovalResolved("call-1", false, "policy"),
                        new SessionEvent.ToolCallStarted("call-1", "dangerous", "{}"),
                        new SessionEvent.ToolCallCompleted(
                                "call-1",
                                new ToolResult("denied by policy", true, 0L))),
                emitted);
    }

    @Test
    void askApprovalExecutesToolAndEmitsAuditEventsBeforeToolEvents() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<Reversibility> approvedReversibility = new AtomicReference<>();
        Tool edit = tool("edit", true, Reversibility.RECOVERABLE, argumentsJson -> {
            executions.incrementAndGet();
            return new ToolResult("edited", false, 0L);
        });

        List<SessionEvent> emitted = execute(
                new ToolRegistry(List.of(edit)),
                (call, reversibility) -> ApprovalDecision.ASK,
                (call, reversibility) -> {
                    approvedReversibility.set(reversibility);
                    return true;
                },
                new StreamChunk.ToolCall("call-1", "edit", "{}"));

        assertEquals(1, executions.get());
        assertEquals(Reversibility.RECOVERABLE, approvedReversibility.get());
        assertEquals(
                List.of(
                        new SessionEvent.ApprovalRequested(
                                "call-1", "edit", Reversibility.RECOVERABLE),
                        new SessionEvent.ApprovalResolved("call-1", true, "user"),
                        new SessionEvent.ToolCallStarted("call-1", "edit", "{}"),
                        new SessionEvent.ToolCallCompleted(
                                "call-1", new ToolResult("edited", false, 0L))),
                emitted);
    }

    @Test
    void rejectedAskDoesNotExecuteTool() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        Tool edit = tool("edit", true, Reversibility.RECOVERABLE, argumentsJson -> {
            executions.incrementAndGet();
            return new ToolResult("edited", false, 0L);
        });

        List<SessionEvent> emitted = execute(
                new ToolRegistry(List.of(edit)),
                (call, reversibility) -> ApprovalDecision.ASK,
                (call, reversibility) -> false,
                new StreamChunk.ToolCall("call-1", "edit", "{}"));

        assertEquals(0, executions.get());
        assertEquals(
                List.of(
                        new SessionEvent.ApprovalRequested(
                                "call-1", "edit", Reversibility.RECOVERABLE),
                        new SessionEvent.ApprovalResolved("call-1", false, "user-denied"),
                        new SessionEvent.ToolCallStarted("call-1", "edit", "{}"),
                        new SessionEvent.ToolCallCompleted(
                                "call-1", new ToolResult("denied by user", true, 0L))),
                emitted);
    }

    @Test
    void unknownToolUsesIrreversibleReversibilityWhenDeciding() throws Exception {
        AtomicReference<Reversibility> requestedReversibility = new AtomicReference<>();

        List<SessionEvent> emitted = execute(
                ToolRegistry.empty(),
                (call, reversibility) -> {
                    requestedReversibility.set(reversibility);
                    return ApprovalDecision.ASK;
                },
                (call, reversibility) -> true,
                new StreamChunk.ToolCall("call-1", "missing", "{}"));

        assertEquals(Reversibility.IRREVERSIBLE, requestedReversibility.get());
        assertEquals(
                new SessionEvent.ApprovalRequested(
                        "call-1", "missing", Reversibility.IRREVERSIBLE),
                emitted.getFirst());
        assertEquals("unknown tool: missing", completedResults(emitted).getFirst().content());
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
    void deniedCallsDoNotForceApprovedParallelToolsToRunSerially() throws Exception {
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
        AtomicInteger deniedExecutions = new AtomicInteger();
        Tool denied = tool("denied", false, argumentsJson -> {
            deniedExecutions.incrementAndGet();
            return new ToolResult("denied", false, 0L);
        });

        List<SessionEvent> emitted = execute(
                new ToolRegistry(List.of(left, right, denied)),
                (call, reversibility) -> call.toolName().equals("denied")
                        ? ApprovalDecision.DENY
                        : ApprovalDecision.ALLOW,
                ApprovalGate.denyAll(),
                new StreamChunk.ToolCall("call-1", "left", "{}"),
                new StreamChunk.ToolCall("call-2", "denied", "{}"),
                new StreamChunk.ToolCall("call-3", "right", "{}"));

        assertEquals(0, deniedExecutions.get());
        assertEquals(
                List.of("left", "denied by policy", "right"),
                completedResults(emitted).stream().map(ToolResult::content).toList());
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
    void usesArgumentsWhenDecidingToolReversibility() throws Exception {
        AtomicReference<Reversibility> approvedReversibility = new AtomicReference<>();
        Tool file = new Tool() {
            @Override
            public String name() {
                return "file";
            }

            @Override
            public ToolResult execute(String argumentsJson) {
                return new ToolResult("done", false, 0L);
            }

            @Override
            public Reversibility reversibility(String argumentsJson) {
                return argumentsJson.contains("write")
                        ? Reversibility.RECOVERABLE
                        : Reversibility.REVERSIBLE;
            }
        };

        execute(
                new ToolRegistry(List.of(file)),
                (call, reversibility) -> ApprovalDecision.ASK,
                (call, reversibility) -> {
                    approvedReversibility.set(reversibility);
                    return true;
                },
                new StreamChunk.ToolCall(
                        "call-1", "file", "{\"op\":\"write\",\"path\":\"a.txt\"}"));

        assertEquals(Reversibility.RECOVERABLE, approvedReversibility.get());
    }

    @Test
    void usesArgumentsWhenDecidingToolParallelism() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        Tool file = new Tool() {
            @Override
            public String name() {
                return "file";
            }

            @Override
            public ToolResult execute(String argumentsJson) {
                int current = active.incrementAndGet();
                maxActive.accumulateAndGet(current, Math::max);
                pause(50L);
                active.decrementAndGet();
                return new ToolResult(argumentsJson, false, 0L);
            }

            @Override
            public boolean parallelizable(String argumentsJson) {
                return argumentsJson.contains("read");
            }
        };

        execute(
                new ToolRegistry(List.of(file)),
                new StreamChunk.ToolCall("call-1", "file", "{\"op\":\"read\"}"),
                new StreamChunk.ToolCall("call-2", "file", "{\"op\":\"write\"}"));

        assertEquals(1, maxActive.get());
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
        ActNode node = new ActNode(
                new ToolRegistry(List.of(timed)),
                (request, ignored) -> null,
                ALLOW_ALL,
                ApprovalGate.denyAll());

        assertNull(node.apply(new RunRequest("session", "input", 0), context));

        assertTrue(context.getPendingToolCalls().isEmpty());
        assertTrue(context.isToolExecuted());
        ToolResult result = completedResults(emitted).getFirst();
        assertFalse(result.isError());
        assertTrue(result.durationMs() >= 25L);
    }

    private static List<SessionEvent> execute(
            ToolRegistry registry, StreamChunk.ToolCall... calls) throws Exception {
        return execute(registry, ALLOW_ALL, ApprovalGate.denyAll(), calls);
    }

    private static List<SessionEvent> execute(
            ToolRegistry registry,
            ApprovalPolicy policy,
            ApprovalGate gate,
            StreamChunk.ToolCall... calls) throws Exception {
        List<SessionEvent> emitted = new ArrayList<>();
        RunContext context = new RunContext("session", emitted::add);
        context.setPendingToolCalls(List.of(calls));
        ActNode node = new ActNode(registry, (request, ignored) -> null, policy, gate);

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
        return tool(name, parallelizable, Reversibility.IRREVERSIBLE, behavior);
    }

    private static Tool tool(
            String name,
            boolean parallelizable,
            Reversibility reversibility,
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

            @Override
            public Reversibility reversibility() {
                return reversibility;
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
