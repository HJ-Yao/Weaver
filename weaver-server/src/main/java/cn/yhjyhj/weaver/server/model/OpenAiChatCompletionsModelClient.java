package cn.yhjyhj.weaver.server.model;

import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.model.ModelMessage;
import cn.yhjyhj.weaver.core.model.ModelRequest;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.ToolSpec;
import cn.yhjyhj.weaver.server.config.WeaverProperties.ModelProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通过 OpenAI Chat Completions 协议访问兼容模型端点。
 *
 * <p>客户端把 {@link ModelRequest} 转成 OpenAI messages/tools 结构，并把 SSE
 * 增量转换为 {@link StreamChunk}。每个请求使用独立的虚拟读取线程；工具调用参数
 * 按 {@code index} 聚合，在流结束时一次性交给下游。
 */
public final class OpenAiChatCompletionsModelClient implements ModelClient {

    private static final ObjectMapper JSON = new ObjectMapper(); // JSON 请求与 SSE 负载编解码器
    private static final AtomicLong STREAM_IDS = new AtomicLong(); // 生成可辨认的虚拟线程名

    private final ModelProperties properties; // 模型端点、密钥与生成参数
    private final HttpSseTransport transport; // 可注入的 SSE 传输边界
    private final URI endpoint; // 已追加 chat/completions 的完整请求地址

    /**
     * 使用应用配置创建客户端，并建立默认 HTTP SSE 传输层。
     *
     * @param properties 模型配置
     */
    public OpenAiChatCompletionsModelClient(ModelProperties properties) {
        this(properties, new HttpSseTransport(
                Duration.ofSeconds(properties.timeoutSeconds())));
    }

    /**
     * 使用指定 SSE 传输层创建客户端，供测试或自定义连接策略使用。
     *
     * @param properties 模型配置
     * @param transport SSE 传输层
     */
    public OpenAiChatCompletionsModelClient(
            ModelProperties properties,
            HttpSseTransport transport) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.endpoint = chatCompletionsEndpoint(properties.baseUrl());
    }

    /**
     * 发起流式补全请求。
     *
     * <p>请求体在订阅前完成构造；工具 JSON Schema 不合法时同步抛出
     * {@link IllegalArgumentException}。API key 为空时不会发起网络请求，而是通过
     * Publisher 报告配置错误。网络或解析失败统一进入 {@code onError}；正常结束时
     * 先输出聚合后的工具调用，再输出 {@link StreamChunk.Done}。
     *
     * @param request 本轮模型输入、历史与工具清单
     * @return 带背压的模型响应分片流
     */
    @Override
    public Flow.Publisher<StreamChunk> stream(ModelRequest request) {
        Objects.requireNonNull(request, "request");
        if (isBlank(properties.apiKey())) {
            return failedPublisher(new IllegalStateException(
                    "model api key is not configured"));
        }

        String jsonBody = requestBody(request);
        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            SubmissionPublisher<StreamChunk> output = new SubmissionPublisher<>();
            AtomicReference<Flow.Subscription> transportSubscription = new AtomicReference<>();
            AtomicReference<Thread> worker = new AtomicReference<>();
            AtomicBoolean cancelled = new AtomicBoolean();
            AtomicBoolean terminal = new AtomicBoolean();
            Thread readingThread = Thread.ofVirtual()
                    .name("weaver-model-" + STREAM_IDS.incrementAndGet())
                    .unstarted(() -> readModelStream(
                            jsonBody,
                            output,
                            transportSubscription,
                            cancelled,
                            terminal));
            worker.set(readingThread);

            output.subscribe(new Flow.Subscriber<>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override
                        public void request(long count) {
                            subscription.request(count);
                        }

                        @Override
                        public void cancel() {
                            cancelled.set(true);
                            subscription.cancel();
                            Flow.Subscription upstream = transportSubscription.get();
                            if (upstream != null) {
                                upstream.cancel();
                            }
                            worker.get().interrupt();
                        }
                    });
                }

                @Override
                public void onNext(StreamChunk item) {
                    subscriber.onNext(item);
                }

                @Override
                public void onError(Throwable throwable) {
                    subscriber.onError(throwable);
                }

                @Override
                public void onComplete() {
                    subscriber.onComplete();
                }
            });

            if (!cancelled.get()) {
                readingThread.start();
            }
        };
    }

    private void readModelStream(
            String jsonBody,
            SubmissionPublisher<StreamChunk> output,
            AtomicReference<Flow.Subscription> transportSubscription,
            AtomicBoolean cancelled,
            AtomicBoolean terminal) {
        ParseState state = new ParseState();
        transport.post(endpoint, requestHeaders(), jsonBody).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                transportSubscription.set(subscription);
                if (cancelled.get()) {
                    subscription.cancel();
                    return;
                }
                subscription.request(1);
            }

            @Override
            public void onNext(String payload) {
                if (cancelled.get()) {
                    return;
                }
                try {
                    boolean done = processPayload(payload, state, output);
                    if (done) {
                        terminal.set(true);
                        transportSubscription.get().cancel();
                        output.close();
                        return;
                    }
                    transportSubscription.get().request(1);
                } catch (RuntimeException exception) {
                    fail(output, terminal, exception);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                fail(output, terminal, throwable);
            }

            @Override
            public void onComplete() {
                if (cancelled.get()) {
                    return;
                }
                terminal.set(true);
                try {
                    emitAggregatedToolCalls(state, output);
                    output.submit(new StreamChunk.Done(state.stopReason));
                    output.close();
                } catch (RuntimeException exception) {
                    output.closeExceptionally(exception);
                }
            }
        });
    }

    private boolean processPayload(
            String payload,
            ParseState state,
            SubmissionPublisher<StreamChunk> output) {
        if ("[DONE]".equals(payload)) {
            emitAggregatedToolCalls(state, output);
            output.submit(new StreamChunk.Done(state.stopReason));
            return true;
        }

        JsonNode root;
        try {
            root = JSON.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid SSE JSON payload", exception);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return false;
        }

        JsonNode choice = choices.get(0);
        JsonNode delta = choice.path("delta");
        JsonNode content = delta.path("content");
        if (content.isTextual()) {
            String text = content.asText();
            if (!text.isEmpty()) {
                output.submit(new StreamChunk.Text(text));
            }
        }

        JsonNode toolCalls = delta.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode toolCall : toolCalls) {
                appendToolCall(toolCall, state.toolCalls);
            }
        }

        JsonNode finishReason = choice.path("finish_reason");
        if (finishReason.isTextual() && !finishReason.asText().isBlank()) {
            state.stopReason = mapStopReason(finishReason.asText());
        }
        return false;
    }

    private static void appendToolCall(
            JsonNode toolCall,
            Map<Integer, ToolCallAccumulator> accumulated) {
        int index = toolCall.path("index").asInt(0);
        ToolCallAccumulator target =
                accumulated.computeIfAbsent(index, ignored -> new ToolCallAccumulator());
        appendText(toolCall.path("id"), target::appendId);

        JsonNode function = toolCall.path("function");
        appendText(function.path("name"), target::appendName);
        appendText(function.path("arguments"), target::appendArguments);
    }

    private static void appendText(JsonNode value, java.util.function.Consumer<String> target) {
        if (value.isTextual() && !value.asText().isEmpty()) {
            target.accept(value.asText());
        }
    }

    private static void emitAggregatedToolCalls(
            ParseState state,
            SubmissionPublisher<StreamChunk> output) {
        state.toolCalls.values().forEach(call -> output.submit(
                new StreamChunk.ToolCall(call.callId.toString(), call.name.toString(),
                        call.arguments.toString())));
    }

    private static StreamChunk.StopReason mapStopReason(String finishReason) {
        return switch (finishReason) {
            case "length" -> StreamChunk.StopReason.LENGTH;
            case "stop", "tool_calls" -> StreamChunk.StopReason.STOP;
            default -> StreamChunk.StopReason.STOP;
        };
    }

    private String requestBody(ModelRequest request) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", properties.model());
        body.put("stream", true);
        body.put("max_tokens", properties.maxTokens());
        body.put("temperature", properties.temperature());
        body.set("messages", messages(request));
        body.set("tools", tools(request.tools()));
        return body.toString();
    }

    private static ArrayNode messages(ModelRequest request) {
        ArrayNode messages = JSON.createArrayNode();
        for (ModelMessage message : request.history()) {
            ObjectNode converted = JSON.createObjectNode();
            switch (message) {
                case ModelMessage.User user -> {
                    converted.put("role", "user");
                    converted.put("content", user.content());
                }
                case ModelMessage.Assistant assistant -> {
                    converted.put("role", "assistant");
                    converted.put("content", assistant.content());
                    if (!assistant.toolCalls().isEmpty()) {
                        ArrayNode toolCalls = JSON.createArrayNode();
                        for (StreamChunk.ToolCall call : assistant.toolCalls()) {
                            ObjectNode convertedCall = JSON.createObjectNode();
                            convertedCall.put("id", call.callId());
                            convertedCall.put("type", "function");
                            ObjectNode function = JSON.createObjectNode();
                            function.put("name", call.toolName());
                            function.put("arguments", call.argumentsJson());
                            convertedCall.set("function", function);
                            toolCalls.add(convertedCall);
                        }
                        converted.set("tool_calls", toolCalls);
                    }
                }
                case ModelMessage.Tool tool -> {
                    converted.put("role", "tool");
                    converted.put("tool_call_id", tool.callId());
                    converted.put("content", tool.content());
                }
            }
            messages.add(converted);
        }
        return messages;
    }

    private static ArrayNode tools(java.util.List<ToolSpec> specs) {
        ArrayNode tools = JSON.createArrayNode();
        for (ToolSpec spec : specs) {
            ObjectNode converted = JSON.createObjectNode();
            converted.put("type", "function");
            ObjectNode function = JSON.createObjectNode();
            function.put("name", spec.name());
            function.put("description", spec.description());

            JsonNode parameters;
            try {
                parameters = JSON.readTree(spec.parametersSchema());
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException(
                        "invalid tool parameters schema: " + spec.name(), exception);
            }
            if (parameters == null || !parameters.isObject()) {
                throw new IllegalArgumentException(
                        "tool parameters schema must be a JSON object: " + spec.name());
            }
            function.set("parameters", parameters);
            converted.set("function", function);
            tools.add(converted);
        }
        return tools;
    }

    private Map<String, String> requestHeaders() {
        return Map.of(
                "Authorization", "Bearer " + properties.apiKey(),
                "Content-Type", "application/json",
                "Accept", "text/event-stream");
    }

    private static URI chatCompletionsEndpoint(URI baseUrl) {
        String prefix = baseUrl.toString();
        if (prefix.endsWith("/")) {
            return URI.create(prefix + "chat/completions");
        }
        return URI.create(prefix + "/chat/completions");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void fail(
            SubmissionPublisher<StreamChunk> output,
            AtomicBoolean terminal,
            Throwable throwable) {
        terminal.set(true);
        output.closeExceptionally(throwable);
    }

    private static Flow.Publisher<StreamChunk> failedPublisher(Throwable error) {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {}

                @Override
                public void cancel() {}
            });
            subscriber.onError(error);
        };
    }

    private static final class ParseState {

        private final Map<Integer, ToolCallAccumulator> toolCalls = new TreeMap<>(); // 按 index 聚合
        private StreamChunk.StopReason stopReason = StreamChunk.StopReason.STOP; // 默认自然结束
    }

    private static final class ToolCallAccumulator {

        private final StringBuilder callId = new StringBuilder(); // 首块或分片给出的调用标识
        private final StringBuilder name = new StringBuilder(); // 首块或分片给出的工具名
        private final StringBuilder arguments = new StringBuilder(); // 跨分片拼接的参数 JSON

        private void appendId(String fragment) {
            callId.append(fragment);
        }

        private void appendName(String fragment) {
            name.append(fragment);
        }

        private void appendArguments(String fragment) {
            arguments.append(fragment);
        }
    }
}
