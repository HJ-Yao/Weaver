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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpenAiChatCompletionsModelClientTest {

    private static final ObjectMapper JSON = new ObjectMapper(); // 校验请求体结构的测试解析器

    @Test
    void streamsTextAndDoneFromDataLines() throws Exception {
        String response = """
                : keep-alive
                event: message
                data: {"choices":[{"delta":{"content":"hi"}}]}

                data: [DONE]

                """;
        try (StubServer server = StubServer.start(200, response);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            OpenAiChatCompletionsModelClient client =
                    new OpenAiChatCompletionsModelClient(properties(server, "test-key"), transport);
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
    void concatenatesIncrementalToolArguments() throws Exception {
        String response = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"file","arguments":"{\\"path\\":"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"a.txt\\"}"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"","function":{"name":"","arguments":""}}]}}]}

                data: [DONE]

                """;
        try (StubServer server = StubServer.start(200, response);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            OpenAiChatCompletionsModelClient client =
                    new OpenAiChatCompletionsModelClient(properties(server, "test-key"), transport);
            RecordingSubscriber subscriber = new RecordingSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);
            subscriber.await();

            assertEquals(
                    List.of(
                            new StreamChunk.ToolCall(
                                    "call-1", "file", "{\"path\":\"a.txt\"}"),
                            new StreamChunk.Done(StreamChunk.StopReason.STOP)),
                    subscriber.chunks);
        }
    }

    @Test
    void aggregatesParallelToolCallsByIndex() throws Exception {
        String response = """
                data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call-b","type":"function","function":{"name":"shell","arguments":"{}"}},{"index":0,"id":"call-a","type":"function","function":{"name":"file","arguments":"{\\"path\\":\\"a.txt\\"}"}}]}}]}

                data: [DONE]

                """;
        try (StubServer server = StubServer.start(200, response);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            OpenAiChatCompletionsModelClient client =
                    new OpenAiChatCompletionsModelClient(properties(server, "test-key"), transport);
            RecordingSubscriber subscriber = new RecordingSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);
            subscriber.await();

            assertEquals(
                    List.of(
                            new StreamChunk.ToolCall("call-a", "file", "{\"path\":\"a.txt\"}"),
                            new StreamChunk.ToolCall("call-b", "shell", "{}"),
                            new StreamChunk.Done(StreamChunk.StopReason.STOP)),
                    subscriber.chunks);
        }
    }

    @Test
    void mapsLengthFinishReason() throws Exception {
        String response = """
                data: {"choices":[{"delta":{},"finish_reason":"length"}]}

                data: [DONE]

                """;
        try (StubServer server = StubServer.start(200, response);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            OpenAiChatCompletionsModelClient client =
                    new OpenAiChatCompletionsModelClient(properties(server, "test-key"), transport);
            RecordingSubscriber subscriber = new RecordingSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);
            subscriber.await();

            assertEquals(
                    List.of(new StreamChunk.Done(StreamChunk.StopReason.LENGTH)),
                    subscriber.chunks);
        }
    }

    @Test
    void emitsDoneWhenTransportCompletesWithoutSentinel() throws Exception {
        String response = """
                data: {"choices":[{"delta":{"content":"tail"}}]}

                """;
        try (StubServer server = StubServer.start(200, response);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            OpenAiChatCompletionsModelClient client =
                    new OpenAiChatCompletionsModelClient(properties(server, "test-key"), transport);
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
    void mapsToolCallsAndUnknownFinishReasonsToStop() throws Exception {
        String response = """
                data: {"choices":[{"delta":{"content":""}}]}
                data: {"choices":[]}
                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
                data: {"choices":[{"delta":{},"finish_reason":""}]}
                data: {"choices":[{"delta":{},"finish_reason":"content_filter"}]}
                data: [DONE]

                """;
        try (StubServer server = StubServer.start(200, response);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            OpenAiChatCompletionsModelClient client =
                    new OpenAiChatCompletionsModelClient(properties(server, "test-key"), transport);
            RecordingSubscriber subscriber = new RecordingSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);
            subscriber.await();

            assertEquals(
                    List.of(new StreamChunk.Done(StreamChunk.StopReason.STOP)),
                    subscriber.chunks);
        }
    }

    @Test
    void acceptsTrailingSlashBaseUrlAndStopFinishReason() throws Exception {
        String response = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
        try (StubServer server = StubServer.start(200, response);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            ModelProperties properties = new ModelProperties(
                    "openai-chat-completions",
                    URI.create(server.baseUri + "/"),
                    "test-key",
                    "deepseek-chat",
                    4096,
                    0.7,
                    5);
            OpenAiChatCompletionsModelClient client =
                    new OpenAiChatCompletionsModelClient(properties, transport);
            RecordingSubscriber subscriber = new RecordingSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);
            subscriber.await();

            assertEquals(
                    List.of(new StreamChunk.Done(StreamChunk.StopReason.STOP)),
                    subscriber.chunks);
        }
    }

    @Test
    void reportsMalformedSseJsonAsPublisherError() throws Exception {
        try (StubServer server = StubServer.start(200, "data: not-json\n\ndata: [DONE]\n\n");
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            OpenAiChatCompletionsModelClient client =
                    new OpenAiChatCompletionsModelClient(properties(server, "test-key"), transport);
            RecordingSubscriber subscriber = new RecordingSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);
            subscriber.await();

            assertInstanceOf(IllegalArgumentException.class, subscriber.error.get());
        }
    }

    @Test
    void reportsHttpErrorWithStatusAndBody() throws Exception {
        try (StubServer server = StubServer.start(500, "upstream broke");
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            OpenAiChatCompletionsModelClient client =
                    new OpenAiChatCompletionsModelClient(properties(server, "test-key"), transport);
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
    void capsHttpErrorBodyAtFourKilobytes() throws Exception {
        String largeBody = String.join("\n", Collections.nCopies(100, "x".repeat(100)));
        try (StubServer server = StubServer.start(500, largeBody);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            OpenAiChatCompletionsModelClient client =
                    new OpenAiChatCompletionsModelClient(properties(server, "test-key"), transport);
            RecordingSubscriber subscriber = new RecordingSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);
            subscriber.await();

            assertTrue(subscriber.error.get().getMessage().length() < 4200);
        }
    }

    @Test
    void rejectsMissingApiKeyWithoutSendingRequest() throws Exception {
        try (StubServer server = StubServer.start(200, "data: [DONE]\n\n");
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            OpenAiChatCompletionsModelClient client =
                    new OpenAiChatCompletionsModelClient(properties(server, ""), transport);
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
        try (StubServer server = StubServer.start(200, "data: [DONE]\n\n");
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            OpenAiChatCompletionsModelClient client =
                    new OpenAiChatCompletionsModelClient(properties(server, null), transport);
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
    void mapsMessagesAndToolsToChatCompletionsRequest() throws Exception {
        String response = "data: [DONE]\n\n";
        List<ModelMessage> history = List.of(
                new ModelMessage.User("inspect"),
                new ModelMessage.Assistant(
                        "",
                        List.of(new StreamChunk.ToolCall(
                                "call-1", "file", "{\"path\":\"a.txt\"}"))),
                new ModelMessage.Tool("call-1", "file contents", true),
                new ModelMessage.Assistant("finished", List.of()));
        List<ToolSpec> tools = List.of(new ToolSpec(
                "file",
                "Read a workspace file",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"));

        try (StubServer server = StubServer.start(200, response);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            OpenAiChatCompletionsModelClient client =
                    new OpenAiChatCompletionsModelClient(properties(server, "test-key"), transport);
            RecordingSubscriber subscriber = new RecordingSubscriber();

            client.stream(new ModelRequest("", history, tools)).subscribe(subscriber);
            subscriber.await();

            JsonNode request = JSON.readTree(server.requestBody.get());
            assertEquals("deepseek-chat", request.path("model").asText());
            assertTrue(request.path("stream").asBoolean());
            assertEquals(4096, request.path("max_tokens").asInt());
            assertEquals(0.7, request.path("temperature").asDouble());

            JsonNode messages = request.path("messages");
            assertEquals("user", messages.get(0).path("role").asText());
            assertEquals("inspect", messages.get(0).path("content").asText());
            assertEquals("assistant", messages.get(1).path("role").asText());
            assertEquals("", messages.get(1).path("content").asText());
            assertEquals(
                    "call-1",
                    messages.get(1).path("tool_calls").get(0).path("id").asText());
            assertEquals(
                    "file",
                    messages.get(1)
                            .path("tool_calls")
                            .get(0)
                            .path("function")
                            .path("name")
                            .asText());
            assertEquals("tool", messages.get(2).path("role").asText());
            assertEquals("call-1", messages.get(2).path("tool_call_id").asText());
            assertEquals("file contents", messages.get(2).path("content").asText());
            assertEquals("file", request.path("tools").get(0).path("function").path("name").asText());
            assertTrue(request.path("tools")
                    .get(0)
                    .path("function")
                    .path("parameters")
                    .path("properties")
                    .path("path")
                    .isObject());
            assertEquals("Bearer test-key", server.authorization.get());
            assertEquals("application/json", server.contentType.get());
        }
    }

    @Test
    void rejectsInvalidToolParametersSchema() throws Exception {
        try (StubServer server = StubServer.start(200, "data: [DONE]\n\n");
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            OpenAiChatCompletionsModelClient client =
                    new OpenAiChatCompletionsModelClient(properties(server, "test-key"), transport);
            ModelRequest request = new ModelRequest(
                    "",
                    List.of(),
                    List.of(new ToolSpec("file", "Read a file", "not-json")));

            assertThrows(IllegalArgumentException.class, () -> client.stream(request));
            ModelRequest nonObjectSchema = new ModelRequest(
                    "",
                    List.of(),
                    List.of(new ToolSpec("file", "Read a file", "[]")));
            assertThrows(IllegalArgumentException.class, () -> client.stream(nonObjectSchema));
            assertEquals(0, server.requestCount.get());
        }
    }

    @Test
    void cancellingSubscriberStopsDelivery() throws Exception {
        String response = """
                data: {"choices":[{"delta":{"content":"first"}}]}

                data: {"choices":[{"delta":{"content":"second"}}]}

                data: [DONE]

                """;
        try (StubServer server = StubServer.start(200, response);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            OpenAiChatCompletionsModelClient client =
                    new OpenAiChatCompletionsModelClient(properties(server, "test-key"), transport);
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
        try (StubServer server = StubServer.start(200, "data: [DONE]\n\n");
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            OpenAiChatCompletionsModelClient client =
                    new OpenAiChatCompletionsModelClient(properties(server, "test-key"), transport);
            CancelOnSubscribeSubscriber subscriber = new CancelOnSubscribeSubscriber();

            client.stream(new ModelRequest("", List.of())).subscribe(subscriber);

            assertTrue(subscriber.subscribed.await(5, TimeUnit.SECONDS));
            assertEquals(0, server.requestCount.get());
        }
    }

    private static ModelProperties properties(StubServer server, String apiKey) {
        return new ModelProperties(
                "openai-chat-completions",
                server.baseUri,
                apiKey,
                "deepseek-chat",
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
        private final AtomicBoolean cancelled = new AtomicBoolean(); // 是否已调取消
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

        private final HttpServer server; // 本地兼容端点
        private final URI baseUri; // 客户端配置的 API 前缀
        private final AtomicReference<String> requestBody = new AtomicReference<>(); // 捕获的请求体
        private final AtomicReference<String> authorization = new AtomicReference<>(); // 捕获的鉴权头
        private final AtomicReference<String> contentType = new AtomicReference<>(); // 捕获的内容类型
        private final AtomicInteger requestCount = new AtomicInteger(); // 防止空 key 发请求

        private StubServer(HttpServer server, int status, String responseBody) {
            this.server = server;
            this.baseUri = URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            server.createContext("/v1/chat/completions", exchange -> respond(exchange, status, responseBody));
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
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
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
