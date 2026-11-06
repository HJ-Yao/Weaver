package cn.yhjyhj.weaver.mcp.jsonrpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.mcp.McpException;
import cn.yhjyhj.weaver.mcp.McpTimeoutException;
import cn.yhjyhj.weaver.mcp.transport.McpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class JsonRpcConnectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void timeoutSendsCancellationAndRemovesPendingRequest() {
        FakeTransport transport = new FakeTransport();
        JsonRpcConnection connection = new JsonRpcConnection(transport);
        assertThrows(McpTimeoutException.class, () -> connection.request("slow", MAPPER.createObjectNode(), Duration.ofMillis(20)));
        JsonNode cancellation = transport.sent.stream().map(this::parse).filter(message -> message.has("method")
                && message.get("method").asText().equals("notifications/cancelled")).findFirst().orElseThrow();
        assertEquals(1, cancellation.path("params").path("requestId").asLong());
        connection.close();
    }

    @Test
    void serverRequestWithoutHandlerGetsMethodNotFound() {
        FakeTransport transport = new FakeTransport();
        JsonRpcConnection connection = new JsonRpcConnection(transport);
        transport.inbound.accept("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"unknown\"}");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (transport.sent.isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait();
        JsonNode response = parse(transport.sent.get(0));
        assertEquals(-32601, response.path("error").path("code").asInt());
        connection.close();
    }

    @Test
    void completesSuccessfulAndErrorResponsesByRequestId() {
        FakeTransport success = new FakeTransport();
        success.onSend = request -> {
            JsonNode message = parse(request);
            if (message.has("id")) success.inbound.accept("{\"jsonrpc\":\"2.0\",\"id\":" + message.get("id") + ",\"result\":{\"ok\":true}}");
        };
        JsonRpcConnection successConnection = new JsonRpcConnection(success);
        assertTrue(successConnection.request("ok", null, Duration.ofSeconds(1)).path("ok").asBoolean());
        successConnection.close();

        FakeTransport failure = new FakeTransport();
        failure.onSend = request -> {
            JsonNode message = parse(request);
            if (message.has("id")) failure.inbound.accept("{\"jsonrpc\":\"2.0\",\"id\":" + message.get("id")
                    + ",\"error\":{\"code\":-32001,\"message\":\"failed\"}}");
        };
        JsonRpcConnection failedConnection = new JsonRpcConnection(failure);
        McpException error = assertThrows(McpException.class,
                () -> failedConnection.request("fail", MAPPER.createObjectNode(), Duration.ofSeconds(1)));
        assertEquals(-32001, error.code());
        failedConnection.close();
    }

    @Test
    void dispatchesRegisteredNotificationAndRequestHandlers() throws Exception {
        FakeTransport transport = new FakeTransport();
        JsonRpcConnection connection = new JsonRpcConnection(transport);
        CountDownLatch notified = new CountDownLatch(1);
        connection.onNotification("changed", params -> notified.countDown());
        connection.onRequest("echo", params -> params);
        transport.inbound.accept("{\"jsonrpc\":\"2.0\",\"method\":\"changed\",\"params\":{}}");
        transport.inbound.accept("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"echo\",\"params\":{\"v\":1}}");
        assertTrue(notified.await(1, TimeUnit.SECONDS));
        JsonNode response = awaitMessage(transport, message -> message.path("id").asInt() == 3);
        assertEquals(1, response.path("result").path("v").asInt());
        connection.close();
    }

    @Test
    void handlerFailureReturnsInternalErrorAndMalformedInputIsIgnored() {
        FakeTransport transport = new FakeTransport();
        JsonRpcConnection connection = new JsonRpcConnection(transport);
        connection.onRequest("explode", ignored -> { throw new IllegalStateException("boom"); });
        transport.inbound.accept("not-json");
        transport.inbound.accept("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"explode\"}");
        JsonNode response = awaitMessage(transport, message -> message.path("id").asInt() == 4);
        assertEquals(-32603, response.path("error").path("code").asInt());
        connection.close();
    }

    @Test
    void closeFailsPendingAndFutureRequests() throws Exception {
        FakeTransport transport = new FakeTransport();
        JsonRpcConnection connection = new JsonRpcConnection(transport);
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        var future = executor.submit(() -> connection.request("pending", null, Duration.ofSeconds(10)));
        while (transport.sent.isEmpty()) Thread.onSpinWait();
        transport.onClosed.accept(new IllegalStateException("server gone"));
        java.util.concurrent.ExecutionException pendingError = assertThrows(java.util.concurrent.ExecutionException.class, future::get);
        assertTrue(pendingError.getCause() instanceof McpException);
        assertFalse(connection.isOpen());
        assertThrows(McpException.class, () -> connection.request("later", null, Duration.ofSeconds(1)));
        executor.close();
    }

    @Test
    void notifySerializesOptionalParams() {
        FakeTransport transport = new FakeTransport();
        JsonRpcConnection connection = new JsonRpcConnection(transport);
        connection.notify("empty", null);
        connection.notify("full", MAPPER.createObjectNode().put("value", 2));
        assertFalse(parse(transport.sent.get(0)).has("params"));
        assertEquals(2, parse(transport.sent.get(1)).path("params").path("value").asInt());
        connection.close();
    }

    @Test
    void ignoresUnknownNotificationsResponsesAndInvalidEnvelopes() {
        FakeTransport transport = new FakeTransport();
        JsonRpcConnection connection = new JsonRpcConnection(transport);
        transport.inbound.accept("null");
        transport.inbound.accept("{\"jsonrpc\":\"1.0\",\"method\":\"old\"}");
        transport.inbound.accept("{\"jsonrpc\":\"2.0\",\"method\":\"unknown\"}");
        transport.inbound.accept("{\"jsonrpc\":\"2.0\",\"id\":999,\"result\":{}}");
        transport.inbound.accept("{\"jsonrpc\":\"2.0\",\"id\":\"text\",\"result\":{}}");
        transport.inbound.accept("{\"jsonrpc\":\"2.0\"}");
        assertTrue(connection.isOpen());
        connection.close();
    }

    @Test
    void requestHandlerCanReturnNullAndHideNullExceptionMessage() {
        FakeTransport transport = new FakeTransport();
        JsonRpcConnection connection = new JsonRpcConnection(transport);
        connection.onRequest("empty", ignored -> null);
        connection.onRequest("silent", ignored -> { throw new RuntimeException((String) null); });
        transport.inbound.accept("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"empty\"}");
        transport.inbound.accept("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"silent\"}");
        assertTrue(awaitMessage(transport, message -> message.path("id").asInt() == 5).path("result").isObject());
        assertEquals("Internal error", awaitMessage(transport, message -> message.path("id").asInt() == 6)
                .path("error").path("message").asText());
        connection.close();
    }

    @Test
    void sendFailureAndInterruptedWaitRemovePendingRequests() throws Exception {
        FakeTransport failing = new FakeTransport();
        failing.onSend = ignored -> { throw new McpException("write failed"); };
        JsonRpcConnection failedConnection = new JsonRpcConnection(failing);
        assertThrows(McpException.class, () -> failedConnection.request("fail", null, Duration.ofSeconds(1)));
        failedConnection.close();

        FakeTransport interrupted = new FakeTransport();
        JsonRpcConnection interruptedConnection = new JsonRpcConnection(interrupted);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = Thread.startVirtualThread(() -> {
            try {
                interruptedConnection.request("wait", null, Duration.ofSeconds(10));
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        while (interrupted.sent.isEmpty()) Thread.onSpinWait();
        thread.interrupt();
        thread.join();
        assertTrue(failure.get() instanceof McpException);
        interruptedConnection.close();
    }

    @Test
    void timeoutStillThrowsWhenCancellationCannotBeSent() {
        FakeTransport transport = new FakeTransport();
        transport.onSend = ignored -> {
            if (transport.sent.size() > 1) throw new McpException("closed during cancellation");
        };
        JsonRpcConnection connection = new JsonRpcConnection(transport);
        assertThrows(McpTimeoutException.class, () -> connection.request("slow", null, Duration.ofMillis(10)));
        connection.close();
    }

    private JsonNode awaitMessage(FakeTransport transport, java.util.function.Predicate<JsonNode> predicate) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            for (String sent : transport.sent) {
                JsonNode message = parse(sent);
                if (predicate.test(message)) return message;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("response not received");
    }

    private JsonNode parse(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class FakeTransport implements McpTransport {
        private final CopyOnWriteArrayList<String> sent = new CopyOnWriteArrayList<>();
        private Consumer<String> inbound;
        private Consumer<Throwable> onClosed;
        private Consumer<String> onSend;

        @Override
        public void start(Consumer<String> onMessage, Consumer<Throwable> onClosed) {
            this.inbound = onMessage;
            this.onClosed = onClosed;
        }

        @Override
        public void send(String json) {
            sent.add(json);
            if (onSend != null) onSend.accept(json);
        }

        @Override
        public String diagnostics() {
            return "";
        }

        @Override
        public void close() {
            if (onClosed != null) onClosed.accept(null);
        }
    }
}
