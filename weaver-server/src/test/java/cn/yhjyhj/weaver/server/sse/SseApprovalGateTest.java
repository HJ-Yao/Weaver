package cn.yhjyhj.weaver.server.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SseApprovalGateTest {

    private static final ObjectMapper JSON = new ObjectMapper(); // 验证 SSE 数据的 JSON 字段

    @Test
    void pushesApprovalEventWithToolDetails() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        List<SentEvent> events = new CopyOnWriteArrayList<>();
        SseApprovalGate gate = new SseApprovalGate(
                (eventName, data) -> {
                    events.add(new SentEvent(eventName, data));
                    sent.countDown();
                },
                Duration.ofSeconds(5));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(
                    () -> gate.requestApproval(
                            new StreamChunk.ToolCall(
                                    "call-1", "git", "{\"op\":\"commit\"}"),
                            Reversibility.IRREVERSIBLE),
                    executor);

            assertTrue(sent.await(2, TimeUnit.SECONDS));
            SentEvent event = events.getFirst();
            assertEquals("approval", event.name());
            Map<String, Object> payload = JSON.convertValue(
                    event.data(), new TypeReference<>() {});
            assertEquals("call-1", payload.get("callId"));
            assertEquals("git", payload.get("toolName"));
            assertEquals("{\"op\":\"commit\"}", payload.get("argumentsJson"));
            assertEquals("IRREVERSIBLE", payload.get("reversibility"));
            assertEquals(5L, ((Number) payload.get("timeoutSeconds")).longValue());

            assertTrue(gate.resolve("call-1", true));
            assertTrue(result.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void returnsUserApprovalDecision() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        SseApprovalGate gate = new SseApprovalGate(
                (eventName, data) -> sent.countDown(),
                Duration.ofSeconds(5));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Boolean> result = requestAsync(gate, executor, "call-true");
            assertTrue(sent.await(2, TimeUnit.SECONDS));

            assertTrue(gate.resolve("call-true", true));
            assertTrue(result.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void returnsUserDenialDecision() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        SseApprovalGate gate = new SseApprovalGate(
                (eventName, data) -> sent.countDown(),
                Duration.ofSeconds(5));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Boolean> result = requestAsync(gate, executor, "call-false");
            assertTrue(sent.await(2, TimeUnit.SECONDS));

            assertTrue(gate.resolve("call-false", false));
            assertFalse(result.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void returnsFalseWhenResolvingUnknownCall() {
        SseApprovalGate gate = new SseApprovalGate(
                (eventName, data) -> {},
                Duration.ofSeconds(5));

        assertFalse(gate.resolve("missing", true));
    }

    @Test
    void deniesWhenApprovalTimesOut() {
        SseApprovalGate gate = new SseApprovalGate(
                (eventName, data) -> {},
                Duration.ofMillis(25));

        assertFalse(gate.requestApproval(
                new StreamChunk.ToolCall(
                        "call-timeout", "shell", "{\"command\":\"pwd\"}"),
                Reversibility.IRREVERSIBLE));
    }

    @Test
    void deniesImmediatelyWhenApprovalEventCannotBeSent() throws Exception {
        SseApprovalGate gate = new SseApprovalGate(
                (eventName, data) -> {
                    throw new IOException("client disconnected");
                },
                Duration.ofSeconds(30));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Boolean> result = requestAsync(gate, executor, "call-failed");

            assertFalse(result.get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void deniesAndRestoresInterruptWhenWaitingIsInterrupted() throws Exception {
        SseApprovalGate gate = new SseApprovalGate(
                (eventName, data) -> {},
                Duration.ofSeconds(30));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AtomicBoolean interruptRestored = new AtomicBoolean();
            CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(() -> {
                Thread.currentThread().interrupt();
                boolean approved = gate.requestApproval(
                        new StreamChunk.ToolCall(
                                "call-interrupted", "shell", "{\"command\":\"pwd\"}"),
                        Reversibility.IRREVERSIBLE);
                interruptRestored.set(Thread.currentThread().isInterrupted());
                return approved;
            }, executor);

            assertFalse(result.get(1, TimeUnit.SECONDS));
            assertTrue(interruptRestored.get());
        }
    }

    @Test
    void cancelsAllPendingApprovals() throws Exception {
        CountDownLatch sent = new CountDownLatch(2);
        SseApprovalGate gate = new SseApprovalGate(
                (eventName, data) -> sent.countDown(),
                Duration.ofSeconds(5));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Boolean> first = requestAsync(gate, executor, "call-1");
            CompletableFuture<Boolean> second = requestAsync(gate, executor, "call-2");
            assertTrue(sent.await(2, TimeUnit.SECONDS));

            gate.cancelAll();

            assertFalse(first.get(2, TimeUnit.SECONDS));
            assertFalse(second.get(2, TimeUnit.SECONDS));
            assertFalse(gate.resolve("call-1", true));
        }
    }

    @Test
    void keepsParallelApprovalsIndependent() throws Exception {
        CountDownLatch sent = new CountDownLatch(2);
        SseApprovalGate gate = new SseApprovalGate(
                (eventName, data) -> sent.countDown(),
                Duration.ofSeconds(5));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<Boolean> first = requestAsync(gate, executor, "call-1");
            CompletableFuture<Boolean> second = requestAsync(gate, executor, "call-2");
            assertTrue(sent.await(2, TimeUnit.SECONDS));

            assertTrue(gate.resolve("call-1", true));
            assertTrue(first.get(2, TimeUnit.SECONDS));
            assertFalse(second.isDone());

            assertTrue(gate.resolve("call-2", false));
            assertFalse(second.get(2, TimeUnit.SECONDS));
        }
    }

    private static CompletableFuture<Boolean> requestAsync(
            SseApprovalGate gate, ExecutorService executor, String callId) {
        return CompletableFuture.supplyAsync(
                () -> gate.requestApproval(
                        new StreamChunk.ToolCall(
                                callId, "shell", "{\"command\":\"pwd\"}"),
                        Reversibility.IRREVERSIBLE),
                executor);
    }

    private record SentEvent(String name, Object data) {}
}
