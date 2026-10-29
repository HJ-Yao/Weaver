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
 * 通过 Anthropic Messages 协议访问官方或兼容模型端点。
 *
 * <p>客户端把 Weaver 的结构化消息转换为 Anthropic 的 text、tool_use 与 tool_result
 * 内容块，并把 SSE 中的文本增量直接下发。工具参数按内容块 index 聚合，在线程结束前统一
 * 输出为 {@link StreamChunk.ToolCall}，随后输出 {@link StreamChunk.Done}。
 */
public final class AnthropicMessagesModelClient implements ModelClient {

    private static final ObjectMapper JSON = new ObjectMapper(); // JSON 请求与 SSE 负载编解码器
    private static final AtomicLong STREAM_IDS = new AtomicLong(); // 生成可辨识的虚拟线程名
    private final ModelProperties properties; // 模型端点、密钥与生成参数
    private final HttpSseTransport transport; // 可注入的 SSE 传输边界
    private final URI endpoint; // 已追加 v1/messages 的完整请求地址

    /**
     * 使用应用配置创建客户端，并建立带配置超时的默认 HTTP SSE 传输层。
     *
     * @param properties 模型配置
     */
    public AnthropicMessagesModelClient(ModelProperties properties) {
        this(properties, new HttpSseTransport(
                Duration.ofSeconds(properties.timeoutSeconds())));
    }

    /**
     * 使用指定 SSE 传输层创建客户端，供测试或自定义连接策略使用。
     *
     * @param properties 模型配置
     * @param transport SSE 传输层
     */
    public AnthropicMessagesModelClient(
            ModelProperties properties,
            HttpSseTransport transport) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.endpoint = messagesEndpoint(properties.baseUrl());
    }

    /**
     * 发起 Anthropic Messages 流式请求。
     *
     * <p>请求体在订阅前完成构建；工具 Schema 或历史工具参数不是 JSON 对象时同步抛出
     * {@link IllegalArgumentException}。API key 为空时不发起网络请求，而是通过 Publisher
     * 报告配置错误。正常流在 message_stop 或上游流关闭时先输出已完成的工具调用，再输出
     * Done；协议错误、HTTP 错误与解析失败统一进入 {@code onError}。
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
                    .name("weaver-anthropic-" + STREAM_IDS.incrementAndGet())
                    .unstarted(() -> readModelStream(
                            jsonBody,
                            output,
                            transportSubscription,
                            cancelled,
                            terminal));
            worker.set(readingThread);

            new ModelOutputBridge<StreamChunk>(
                    subscriber,
                    cancelled,
                    () -> {
                        Flow.Subscription upstream = transportSubscription.get();
                        if (upstream != null) {
                            upstream.cancel();
                        }
                        worker.get().interrupt();
                    })
                    .subscribeTo(output);

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
        AnthropicStreamSubscriber bridge = new AnthropicStreamSubscriber(
                output, transportSubscription, cancelled, terminal);
        try {
            transport.post(endpoint, requestHeaders(), jsonBody).subscribe(bridge);
        } catch (RuntimeException exception) {
            fail(output, transportSubscription, terminal, exception);
        }
    }

    /**
     * 消费 Anthropic Messages SSE 事件，并聚合文本与工具调用输出。
     *
     * <p>实例由 {@link #readModelStream} 为单次请求创建并交给
     * {@link HttpSseTransport}，生命周期随传输流终止或下游取消结束。
     */
    private final class AnthropicStreamSubscriber implements Flow.Subscriber<String> {

        private final SubmissionPublisher<StreamChunk> output; // 模型流内部发布者
        private final AtomicReference<Flow.Subscription> transportSubscription; // 上游传输订阅句柄
        private final AtomicBoolean cancelled; // 下游是否已取消
        private final AtomicBoolean terminal; // 是否已发出终止信号
        private final ParseState state = new ParseState(); // 当前响应聚合状态

        private AnthropicStreamSubscriber(
                SubmissionPublisher<StreamChunk> output,
                AtomicReference<Flow.Subscription> transportSubscription,
                AtomicBoolean cancelled,
                AtomicBoolean terminal) {
            this.output = output;
            this.transportSubscription = transportSubscription;
            this.cancelled = cancelled;
            this.terminal = terminal;
        }

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
            if (cancelled.get() || terminal.get()) {
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
                fail(output, transportSubscription, terminal, exception);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (!cancelled.get()) {
                fail(output, transportSubscription, terminal, throwable);
            }
        }

        @Override
        public void onComplete() {
            if (cancelled.get() || !terminal.compareAndSet(false, true)) {
                return;
            }
            try {
                emitCompletedToolCalls(state, output);
                output.submit(new StreamChunk.Done(state.stopReason));
                output.close();
            } catch (RuntimeException exception) {
                output.closeExceptionally(exception);
            }
        }
    }

    private boolean processPayload(
            String payload,
            ParseState state,
            SubmissionPublisher<StreamChunk> output) {
        JsonNode event = parseJson(payload, "invalid SSE JSON payload");
        JsonNode typeNode = event.path("type");
        if (!typeNode.isTextual()) {
            return false;
        }

        switch (typeNode.asText()) {
            case "message_start" -> {
                // 消息元数据不参与 Weaver 的流式输出契约。
            }
            case "content_block_start" -> startContentBlock(event, state);
            case "content_block_delta" -> processContentBlockDelta(event, state, output);
            case "content_block_stop" -> completeContentBlock(event, state);
            case "message_delta" -> {
                state.stopReason = mapStopReason(event.path("delta").path("stop_reason"));
            }
            case "message_stop" -> {
                emitCompletedToolCalls(state, output);
                output.submit(new StreamChunk.Done(state.stopReason));
                return true;
            }
            case "error" -> throw new IllegalStateException(
                    "model error: " + event.path("error").path("message").asText(""));
            default -> {
                // Anthropic 会扩充事件类型；未知事件不改变当前状态。
            }
        }
        return false;
    }

    private static void startContentBlock(JsonNode event, ParseState state) {
        JsonNode block = event.path("content_block");
        if (!"tool_use".equals(block.path("type").asText())) {
            return;
        }
        int index = event.path("index").asInt();
        state.toolCalls.put(index, new ToolCallAccumulator(
                block.path("id").asText(),
                block.path("name").asText()));
    }

    private static void processContentBlockDelta(
            JsonNode event,
            ParseState state,
            SubmissionPublisher<StreamChunk> output) {
        JsonNode delta = event.path("delta");
        switch (delta.path("type").asText()) {
            case "text_delta" -> {
                JsonNode text = delta.path("text");
                if (text.isTextual() && !text.asText().isEmpty()) {
                    output.submit(new StreamChunk.Text(text.asText()));
                }
            }
            case "input_json_delta" -> {
                int index = event.path("index").asInt();
                ToolCallAccumulator accumulator = state.toolCalls.computeIfAbsent(
                        index,
                        ignored -> new ToolCallAccumulator("", ""));
                JsonNode partialJson = delta.path("partial_json");
                if (partialJson.isTextual()) {
                    accumulator.appendArguments(partialJson.asText());
                }
            }
            default -> {
                // 新类型增量不影响当前文本与工具参数状态。
            }
        }
    }

    private static void completeContentBlock(JsonNode event, ParseState state) {
        int index = event.path("index").asInt();
        ToolCallAccumulator accumulator = state.toolCalls.get(index);
        if (accumulator != null) {
            accumulator.complete();
        }
    }

    private static void emitCompletedToolCalls(
            ParseState state,
            SubmissionPublisher<StreamChunk> output) {
        state.toolCalls.forEach((index, call) -> {
            if (call.completed) {
                output.submit(new StreamChunk.ToolCall(
                        call.callId,
                        call.name,
                        call.arguments.toString()));
            }
        });
    }

    private String requestBody(ModelRequest request) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", properties.model());
        body.put("max_tokens", properties.maxTokens());
        body.put("temperature", properties.temperature());
        body.put("stream", true);
        body.set("messages", messages(request.history()));
        body.set("tools", tools(request.tools()));
        return body.toString();
    }

    private static ArrayNode messages(java.util.List<ModelMessage> history) {
        ArrayNode messages = JSON.createArrayNode();
        ArrayNode adjacentToolResults = null;
        for (ModelMessage message : history) {
            switch (message) {
                case ModelMessage.User user -> {
                    ObjectNode converted = userMessage();
                    converted.putArray("content").add(textBlock(user.content()));
                    messages.add(converted);
                    adjacentToolResults = null;
                }
                case ModelMessage.Assistant assistant -> {
                    ObjectNode converted = JSON.createObjectNode();
                    converted.put("role", "assistant");
                    ArrayNode content = converted.putArray("content");
                    if (assistant.content() != null && !assistant.content().isEmpty()) {
                        content.add(textBlock(assistant.content()));
                    }
                    for (StreamChunk.ToolCall call : assistant.toolCalls()) {
                        content.add(toolUseBlock(call));
                    }
                    messages.add(converted);
                    adjacentToolResults = null;
                }
                case ModelMessage.Tool tool -> {
                    if (adjacentToolResults == null) {
                        ObjectNode converted = userMessage();
                        adjacentToolResults = converted.putArray("content");
                        messages.add(converted);
                    }
                    adjacentToolResults.add(toolResultBlock(tool));
                }
            }
        }
        return messages;
    }

    private static ObjectNode userMessage() {
        ObjectNode message = JSON.createObjectNode();
        message.put("role", "user");
        return message;
    }

    private static ObjectNode textBlock(String text) {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "text");
        block.put("text", text);
        return block;
    }

    private static ObjectNode toolUseBlock(StreamChunk.ToolCall call) {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "tool_use");
        block.put("id", call.callId());
        block.put("name", call.toolName());
        block.set("input", jsonObject(
                call.argumentsJson(),
                "tool call arguments must be a JSON object: " + call.toolName()));
        return block;
    }

    private static ObjectNode toolResultBlock(ModelMessage.Tool tool) {
        ObjectNode block = JSON.createObjectNode();
        block.put("type", "tool_result");
        block.put("tool_use_id", tool.callId());
        block.put("content", tool.content());
        block.put("is_error", tool.isError());
        return block;
    }

    private static ArrayNode tools(java.util.List<ToolSpec> specs) {
        ArrayNode tools = JSON.createArrayNode();
        for (ToolSpec spec : specs) {
            ObjectNode converted = JSON.createObjectNode();
            converted.put("name", spec.name());
            converted.put("description", spec.description());
            converted.set("input_schema", jsonObject(
                    spec.parametersSchema(),
                    "tool parameters schema must be a JSON object: " + spec.name()));
            tools.add(converted);
        }
        return tools;
    }

    private static JsonNode jsonObject(String source, String errorMessage) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException(errorMessage);
        }
        JsonNode parsed;
        try {
            parsed = JSON.readTree(source);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(errorMessage, exception);
        }
        if (parsed == null || !parsed.isObject()) {
            throw new IllegalArgumentException(errorMessage);
        }
        return parsed;
    }

    private static JsonNode parseJson(String source, String errorMessage) {
        try {
            JsonNode parsed = JSON.readTree(source);
            if (parsed == null) {
                throw new IllegalArgumentException(errorMessage);
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(errorMessage, exception);
        }
    }

    private Map<String, String> requestHeaders() {
        return Map.of(
                "x-api-key", properties.apiKey(),
                "anthropic-version", "2023-06-01",
                "content-type", "application/json",
                "accept", "text/event-stream");
    }

    private static URI messagesEndpoint(URI baseUrl) {
        String prefix = baseUrl.toString();
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return URI.create(prefix + "/v1/messages");
    }

    private static StreamChunk.StopReason mapStopReason(JsonNode stopReason) {
        if (!stopReason.isTextual()) {
            return StreamChunk.StopReason.STOP;
        }
        return switch (stopReason.asText()) {
            case "max_tokens" -> StreamChunk.StopReason.LENGTH;
            case "end_turn", "tool_use", "stop_sequence" -> StreamChunk.StopReason.STOP;
            default -> StreamChunk.StopReason.STOP;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void fail(
            SubmissionPublisher<StreamChunk> output,
            AtomicReference<Flow.Subscription> transportSubscription,
            AtomicBoolean terminal,
            Throwable throwable) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        Flow.Subscription upstream = transportSubscription.get();
        if (upstream != null) {
            upstream.cancel();
        }
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

        private final Map<Integer, ToolCallAccumulator> toolCalls = new TreeMap<>(); // 按内容块 index 聚合与排序
        private StreamChunk.StopReason stopReason = StreamChunk.StopReason.STOP; // 默认自然结束
    }

    private static final class ToolCallAccumulator {

        private final String callId; // content_block_start 给出的调用标识
        private final String name; // content_block_start 给出的工具名
        private final StringBuilder arguments = new StringBuilder(); // 跨 delta 拼接的原始参数 JSON
        private boolean completed; // content_block_stop 是否已确认参数结束

        private ToolCallAccumulator(String callId, String name) {
            this.callId = callId;
            this.name = name;
        }

        private void appendArguments(String fragment) {
            arguments.append(fragment);
        }

        private void complete() {
            completed = true;
        }
    }
}
