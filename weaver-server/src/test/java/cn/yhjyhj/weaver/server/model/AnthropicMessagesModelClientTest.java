package cn.yhjyhj.weaver.server.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.model.ModelMessage;
import cn.yhjyhj.weaver.core.model.ModelRequest;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.ToolSpec;
import cn.yhjyhj.weaver.server.config.WeaverProperties.ModelProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AnthropicMessagesModelClientTest {

    private static final ObjectMapper JSON = new ObjectMapper(); // 校验请求体结构的测试解析器

    @Test
    void streamsTextAndDoneFromAnthropicSse() throws Exception {
        String response = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1"}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        try (StubServer server = StubServer.start(200, response);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            AnthropicMessagesModelClient client =
                    new AnthropicMessagesModelClient(properties(server, "test-key"), transport);
            RecordingSubscriber subscriber = new RecordingSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);
            subscriber.await();

            assertEquals(
                    List.of(
                            new StreamChunk.Text("hi"),
                            new StreamChunk.Done(StreamChunk.StopReason.STOP)),
                    subscriber.chunks);
            assertTrue(subscriber.completed);
        }
    }

    @Test
    void concatenatesInputJsonDeltasIntoOneToolCall() throws Exception {
        String response = """
                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"file","input":{}}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\":\\""}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"a.txt\\"}"}}

                data: {"type":"content_block_stop","index":0}

                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}

                data: {"type":"message_stop"}

                """;
        try (StubServer server = StubServer.start(200, response);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            AnthropicMessagesModelClient client =
                    new AnthropicMessagesModelClient(properties(server, "test-key"), transport);
            RecordingSubscriber subscriber = new RecordingSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);
            subscriber.await();

            assertEquals(
                    List.of(
                            new StreamChunk.ToolCall(
                                    "toolu_1", "file", "{\"path\":\"a.txt\"}"),
                            new StreamChunk.Done(StreamChunk.StopReason.STOP)),
                    subscriber.chunks);
        }
    }

    @Test
    void aggregatesParallelToolCallsByContentBlockIndex() throws Exception {
        String response = """
                data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_b","name":"shell","input":{}}}

                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{}"}}

                data: {"type":"content_block_stop","index":1}

                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_a","name":"file","input":{}}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\":\\"a.txt\\"}"}}

                data: {"type":"content_block_stop","index":0}

                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}

                data: {"type":"message_stop"}

                """;
        try (StubServer server = StubServer.start(200, response);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            AnthropicMessagesModelClient client =
                    new AnthropicMessagesModelClient(properties(server, "test-key"), transport);
            RecordingSubscriber subscriber = new RecordingSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);
            subscriber.await();

            assertEquals(
                    List.of(
                            new StreamChunk.ToolCall(
                                    "toolu_a", "file", "{\"path\":\"a.txt\"}"),
                            new StreamChunk.ToolCall("toolu_b", "shell", "{}"),
                            new StreamChunk.Done(StreamChunk.StopReason.STOP)),
                    subscriber.chunks);
        }
    }

    @Test
    void mapsMaxTokensToLength() throws Exception {
        String response = """
                data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"}}

                data: {"type":"message_stop"}

                """;
        try (StubServer server = StubServer.start(200, response);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            AnthropicMessagesModelClient client =
                    new AnthropicMessagesModelClient(properties(server, "test-key"), transport);
            RecordingSubscriber subscriber = new RecordingSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);
            subscriber.await();

            assertEquals(
                    List.of(new StreamChunk.Done(StreamChunk.StopReason.LENGTH)),
                    subscriber.chunks);
        }
    }

    @Test
    void ignoresUnknownEventShapesAndMapsStopSequence() throws Exception {
        String response = """
                data: {"event":"unknown"}

                data: {"type":"content_block_start","index":0,"content_block":{"type":"text"}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":""}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"citation_delta"}}

                data: {"type":"content_block_stop","index":7}

                data: {"type":"message_delta","delta":{}}

                data: {"type":"message_delta","delta":{"stop_reason":"stop_sequence"}}

                data: {"type":"message_delta","delta":{"stop_reason":"pause_turn"}}

                data: {"type":"message_stop"}

                """;
        try (StubServer server = StubServer.start(200, response);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            AnthropicMessagesModelClient client =
                    new AnthropicMessagesModelClient(properties(server, "test-key"), transport);
            RecordingSubscriber subscriber = new RecordingSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);
            subscriber.await();

            assertEquals(
                    List.of(new StreamChunk.Done(StreamChunk.StopReason.STOP)),
                    subscriber.chunks);
        }
    }

    @Test
    void emitsDoneWhenTransportClosesWithoutMessageStop() throws Exception {
        String response = """
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"tail"}}

                """;
        try (StubServer server = StubServer.start(200, response);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            AnthropicMessagesModelClient client =
                    new AnthropicMessagesModelClient(properties(server, "test-key"), transport);
            RecordingSubscriber subscriber = new RecordingSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);
            subscriber.await();

            assertEquals(
                    List.of(
                            new StreamChunk.Text("tail"),
                            new StreamChunk.Done(StreamChunk.StopReason.STOP)),
                    subscriber.chunks);
            assertTrue(subscriber.completed);
        }
    }

    @Test
    void mapsMessagesToolsAndHeadersToAnthropicRequest() throws Exception {
        String response = """
                data: {"type":"message_stop"}

                """;
        List<ModelMessage> history = List.of(
                new ModelMessage.User("inspect"),
                new ModelMessage.Assistant(
                        "",
                        List.of(
                                new StreamChunk.ToolCall(
                                        "toolu_1", "file", "{\"path\":\"a.txt\"}"),
                                new StreamChunk.ToolCall("toolu_2", "shell", "{\"cmd\":\"pwd\"}"))),
                new ModelMessage.Tool("toolu_1", "file contents", false),
                new ModelMessage.Tool("toolu_2", "permission denied", true),
                new ModelMessage.Assistant("finished", List.of()));
        List<ToolSpec> tools = List.of(new ToolSpec(
                "file",
                "Read a workspace file",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"));

        try (StubServer server = StubServer.start(200, response);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            ModelProperties properties = new ModelProperties(
                    "anthropic-messages",
                    URI.create(server.baseUri + "/"),
                    "test-key",
                    "claude-sonnet-4-20250514",
                    4096,
                    0.7,
                    5);
            AnthropicMessagesModelClient client =
                    new AnthropicMessagesModelClient(properties, transport);
            RecordingSubscriber subscriber = new RecordingSubscriber();

            client.stream(new ModelRequest("", history, tools)).subscribe(subscriber);
            subscriber.await();

            JsonNode request = JSON.readTree(server.requestBody.get());
            assertEquals("claude-sonnet-4-20250514", request.path("model").asText());
            assertEquals(4096, request.path("max_tokens").asInt());
            assertEquals(0.7, request.path("temperature").asDouble());
            assertTrue(request.path("stream").asBoolean());

            JsonNode messages = request.path("messages");
            assertEquals(4, messages.size());
            assertEquals("user", messages.get(0).path("role").asText());
            assertEquals("text", messages.get(0).path("content").get(0).path("type").asText());
            assertEquals("inspect", messages.get(0).path("content").get(0).path("text").asText());

            JsonNode assistant = messages.get(1);
            assertEquals("assistant", assistant.path("role").asText());
            assertEquals(2, assistant.path("content").size());
            assertEquals("tool_use", assistant.path("content").get(0).path("type").asText());
            assertEquals("toolu_1", assistant.path("content").get(0).path("id").asText());
            assertEquals("file", assistant.path("content").get(0).path("name").asText());
            assertEquals(
                    "a.txt",
                    assistant.path("content").get(0).path("input").path("path").asText());

            JsonNode toolResults = messages.get(2);
            assertEquals("user", toolResults.path("role").asText());
            assertEquals(2, toolResults.path("content").size());
            assertEquals(
                    "tool_result",
                    toolResults.path("content").get(0).path("type").asText());
            assertEquals(
                    "toolu_1",
                    toolResults.path("content").get(0).path("tool_use_id").asText());
            assertEquals(
                    "file contents",
                    toolResults.path("content").get(0).path("content").asText());
            assertEquals(
                    false,
                    toolResults.path("content").get(0).path("is_error").asBoolean());
            assertEquals(
                    true,
                    toolResults.path("content").get(1).path("is_error").asBoolean());

            JsonNode tool = request.path("tools").get(0);
            assertEquals("file", tool.path("name").asText());
            assertEquals("Read a workspace file", tool.path("description").asText());
            assertTrue(tool.path("input_schema").path("properties").path("path").isObject());

            assertEquals("test-key", server.apiKey.get());
            assertEquals("2023-06-01", server.anthropicVersion.get());
            assertEquals("application/json", server.contentType.get());
            assertEquals("text/event-stream", server.accept.get());
        }
    }

    @Test
    void rejectsNonObjectToolArgumentsBeforeSendingRequest() throws Exception {
        try (StubServer server = StubServer.start(200, "data: {\"type\":\"message_stop\"}\n\n");
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            AnthropicMessagesModelClient client =
                    new AnthropicMessagesModelClient(properties(server, "test-key"), transport);
            ModelRequest request = new ModelRequest(
                    "",
                    List.of(new ModelMessage.Assistant(
                            "",
                            List.of(new StreamChunk.ToolCall(
                                    "toolu_1", "file", "[]")))));

            assertThrows(IllegalArgumentException.class, () -> client.stream(request));
            assertEquals(0, server.requestCount.get());
        }
    }

    @Test
    void rejectsInvalidToolSchemaBeforeSendingRequest() throws Exception {
        try (StubServer server = StubServer.start(200, "data: {\"type\":\"message_stop\"}\n\n");
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            AnthropicMessagesModelClient client =
                    new AnthropicMessagesModelClient(properties(server, "test-key"), transport);
            ModelRequest request = new ModelRequest(
                    "",
                    List.of(),
                    List.of(new ToolSpec("file", "Read a file", "not-json")));

            assertThrows(IllegalArgumentException.class, () -> client.stream(request));
            assertEquals(0, server.requestCount.get());
        }
    }

    @Test
    void rejectsMissingApiKeyWithoutSendingRequest() throws Exception {
        try (StubServer server = StubServer.start(200, "data: {\"type\":\"message_stop\"}\n\n");
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            AnthropicMessagesModelClient client =
                    new AnthropicMessagesModelClient(properties(server, ""), transport);
            RecordingSubscriber subscriber = new RecordingSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);
            subscriber.await();

            IllegalStateException error =
                    assertInstanceOf(IllegalStateException.class, subscriber.error.get());
            assertEquals("model api key is not configured", error.getMessage());
            assertEquals(0, server.requestCount.get());
        }
    }

    @Test
    void rejectsNullApiKeyWithoutSendingRequest() throws Exception {
        try (StubServer server = StubServer.start(200, "data: {\"type\":\"message_stop\"}\n\n");
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            AnthropicMessagesModelClient client =
                    new AnthropicMessagesModelClient(properties(server, null), transport);
            RecordingSubscriber subscriber = new RecordingSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);
            subscriber.await();

            assertEquals(
                    "model api key is not configured",
                    subscriber.error.get().getMessage());
            assertEquals(0, server.requestCount.get());
        }
    }

    @Test
    void reportsHttpErrorWithStatusAndBody() throws Exception {
        try (StubServer server = StubServer.start(500, "upstream broke");
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            AnthropicMessagesModelClient client =
                    new AnthropicMessagesModelClient(properties(server, "test-key"), transport);
            RecordingSubscriber subscriber = new RecordingSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);
            subscriber.await();

            IllegalStateException error =
                    assertInstanceOf(IllegalStateException.class, subscriber.error.get());
            assertTrue(error.getMessage().contains("HTTP 500"));
            assertTrue(error.getMessage().contains("upstream broke"));
            assertEquals(1, server.requestCount.get());
        }
    }

    @Test
    void reportsModelErrorEventAsPublisherError() throws Exception {
        String response = """
                data: {"type":"error","error":{"type":"overloaded_error","message":"busy"}}

                """;
        try (StubServer server = StubServer.start(200, response);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            AnthropicMessagesModelClient client =
                    new AnthropicMessagesModelClient(properties(server, "test-key"), transport);
            RecordingSubscriber subscriber = new RecordingSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);
            subscriber.await();

            IllegalStateException error =
                    assertInstanceOf(IllegalStateException.class, subscriber.error.get());
            assertEquals("model error: busy", error.getMessage());
        }
    }

    @Test
    void reportsMalformedSseJsonAsPublisherError() throws Exception {
        try (StubServer server = StubServer.start(200, "data: not-json\n\n");
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            AnthropicMessagesModelClient client =
                    new AnthropicMessagesModelClient(properties(server, "test-key"), transport);
            RecordingSubscriber subscriber = new RecordingSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);
            subscriber.await();

            assertInstanceOf(IllegalArgumentException.class, subscriber.error.get());
        }
    }

    @Test
    void cancellingSubscriberStopsDelivery() throws Exception {
        String response = """
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"first"}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"second"}}

                data: {"type":"message_stop"}

                """;
        try (StubServer server = StubServer.start(200, response);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            AnthropicMessagesModelClient client =
                    new AnthropicMessagesModelClient(properties(server, "test-key"), transport);
            CancelAfterFirstSubscriber subscriber = new CancelAfterFirstSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);
            assertTrue(subscriber.firstChunk.await(5, TimeUnit.SECONDS));
            assertFalse(subscriber.unexpectedChunk.await(200, TimeUnit.MILLISECONDS));

            assertEquals(1, subscriber.count.get());
            assertTrue(subscriber.cancelled.get());
        }
    }

    @Test
    void cancellingInOnSubscribeDoesNotStartRequest() throws Exception {
        try (StubServer server = StubServer.start(200, "data: {\"type\":\"message_stop\"}\n\n");
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            AnthropicMessagesModelClient client =
                    new AnthropicMessagesModelClient(properties(server, "test-key"), transport);
            CancelOnSubscribeSubscriber subscriber = new CancelOnSubscribeSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);

            assertTrue(subscriber.subscribed.await(5, TimeUnit.SECONDS));
            assertEquals(0, server.requestCount.get());
        }
    }

    private static ModelProperties properties(StubServer server, String apiKey) {
        return new ModelProperties(
                "anthropic-messages",
                server.baseUri,
                apiKey,
                "claude-sonnet-4-20250514",
                4096,
                0.7,
                5);
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<StreamChunk> {

        private final List<StreamChunk> chunks =
                Collections.synchronizedList(new ArrayList<>()); // 按到达顺序记录响应分片
        private final CountDownLatch finished = new CountDownLatch(1); // 等待终止信号
        private final AtomicReference<Throwable> error = new AtomicReference<>(); // 终止错误
        private volatile boolean completed; // 是否正常结束

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(StreamChunk item) {
            chunks.add(item);
        }

        @Override
        public void onError(Throwable throwable) {
            error.set(throwable);
            finished.countDown();
        }

        @Override
        public void onComplete() {
            completed = true;
            finished.countDown();
        }

        private void await() throws InterruptedException {
            assertTrue(finished.await(5, TimeUnit.SECONDS), "response did not terminate");
        }
    }

    private static final class CancelAfterFirstSubscriber implements Flow.Subscriber<StreamChunk> {

        private final CountDownLatch firstChunk = new CountDownLatch(1); // 等待首片到达
        private final CountDownLatch unexpectedChunk = new CountDownLatch(1); // 取消后的意外分片
        private final AtomicInteger count = new AtomicInteger(); // 已收到分片数
        private final java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean(); // 是否已取消
        private Flow.Subscription subscription; // 下游订阅句柄

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(StreamChunk item) {
            if (count.incrementAndGet() == 1) {
                subscription.cancel();
                cancelled.set(true);
                firstChunk.countDown();
            } else {
                unexpectedChunk.countDown();
            }
        }

        @Override
        public void onError(Throwable throwable) {}

        @Override
        public void onComplete() {}
    }

    private static final class CancelOnSubscribeSubscriber implements Flow.Subscriber<StreamChunk> {

        private final CountDownLatch subscribed = new CountDownLatch(1); // 确认订阅已建立

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.cancel();
            subscribed.countDown();
        }

        @Override
        public void onNext(StreamChunk item) {}

        @Override
        public void onError(Throwable throwable) {}

        @Override
        public void onComplete() {}
    }

    private static final class StubServer implements AutoCloseable {

        private final HttpServer server; // 本地 Anthropic 兼容端点
        private final URI baseUri; // 客户端配置的 API 前缀
        private final AtomicReference<String> requestBody = new AtomicReference<>(); // 捕获的请求体
        private final AtomicReference<String> apiKey = new AtomicReference<>(); // 捕获的 API key
        private final AtomicReference<String> anthropicVersion = new AtomicReference<>(); // 捕获的协议版本
        private final AtomicReference<String> contentType = new AtomicReference<>(); // 捕获的内容类型
        private final AtomicReference<String> accept = new AtomicReference<>(); // 捕获的响应类型
        private final AtomicInteger requestCount = new AtomicInteger(); // 请求计数

        private StubServer(HttpServer server, int status, String responseBody) {
            this.server = server;
            this.baseUri = URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort());
            server.createContext(
                    "/v1/messages",
                    exchange -> respond(exchange, status, responseBody));
        }

        private static StubServer start(int status, String responseBody) throws IOException {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0), 0);
            StubServer stub = new StubServer(server, status, responseBody);
            server.start();
            return stub;
        }

        private void respond(HttpExchange exchange, int status, String responseBody)
                throws IOException {
            requestCount.incrementAndGet();
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            anthropicVersion.set(
                    exchange.getRequestHeaders().getFirst("anthropic-version"));
            contentType.set(exchange.getRequestHeaders().getFirst("content-type"));
            accept.set(exchange.getRequestHeaders().getFirst("accept"));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
