package cn.yhjyhj.weaver.server.model;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 负责一次 HTTP SSE 请求从订阅建立到终止的完整生命周期。
 *
 * <p>实例由 {@link HttpSseTransport#post(URI, Map, String)} 为每个下游订阅创建，
 * 自身同时充当传输层内部发布者的订阅者和下游订阅句柄。读取线程由本实例启动，
 * 并在正常结束、异常、取消或被传输层关闭时退出。
 */
final class SseExchange implements Flow.Subscription, Flow.Subscriber<String> {

    private static final int ERROR_BODY_LIMIT = 4096; // 非 2xx 响应最多保留 4KB 诊断信息
    private static final AtomicLong EXCHANGE_IDS = new AtomicLong(); // 生成可辨认的虚拟线程名

    private final Flow.Subscriber<? super String> subscriber; // 接收 SSE data 负载的外部订阅者
    private final URI uri; // 完整请求地址
    private final Map<String, String> headers; // 附加请求头
    private final String jsonBody; // JSON 请求体
    private final Duration timeout; // 建连与等待响应的统一超时
    private final HttpClient httpClient; // 复用连接池的 JDK HTTP 客户端
    private final Set<Thread> activeThreads; // 传输层跟踪的活动读取线程
    private final AtomicBoolean closed = new AtomicBoolean(); // 当前交换是否已取消

    private SubmissionPublisher<String> publisher; // 连接读取线程与下游的内部发布者
    private Flow.Subscription publisherSubscription; // 向内部发布者请求数据的句柄
    private Thread readingThread; // 当前交换使用的虚拟读取线程

    SseExchange(
            Flow.Subscriber<? super String> subscriber,
            URI uri,
            Map<String, String> headers,
            String jsonBody,
            Duration timeout,
            HttpClient httpClient,
            Set<Thread> activeThreads) {
        this.subscriber = Objects.requireNonNull(subscriber, "subscriber");
        this.uri = Objects.requireNonNull(uri, "uri");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.jsonBody = Objects.requireNonNull(jsonBody, "jsonBody");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.activeThreads = Objects.requireNonNull(activeThreads, "activeThreads");
    }

    /**
     * 建立内部发布者与下游的桥接，并启动虚拟读取线程。
     *
     * <p>读取线程先创建再触发下游 {@code onSubscribe}，使下游立即取消时仍能中断
     * 尚未启动的线程。取消发生在启动前时仍沿用原流程启动线程，读取阶段自行识别中断。
     */
    void start() {
        publisher = new SubmissionPublisher<>();
        readingThread = Thread.ofVirtual()
                .name("weaver-sse-" + EXCHANGE_IDS.incrementAndGet())
                .unstarted(this::readResponse);
        publisher.subscribe(this);
        activeThreads.add(readingThread);
        readingThread.start();
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        publisherSubscription = subscription;
        subscriber.onSubscribe(this);
    }

    @Override
    public void onNext(String item) {
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

    @Override
    public void request(long count) {
        publisherSubscription.request(count);
    }

    @Override
    public void cancel() {
        closed.set(true);
        publisherSubscription.cancel();
        readingThread.interrupt();
    }

    private void readResponse() {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                    .timeout(timeout);
            headers.forEach(requestBuilder::header);
            HttpRequest request = requestBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Stream<String>> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

            if (closed.get() || Thread.currentThread().isInterrupted()) {
                response.body().close();
                return;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String body = readErrorBody(response.body());
                publisher.closeExceptionally(new IllegalStateException(
                        "HTTP " + response.statusCode() + ": " + body));
                return;
            }

            try (Stream<String> lines = response.body()) {
                lines.map(SseExchange::dataPayload)
                        .filter(Objects::nonNull)
                        .forEach(publisher::submit);
            }
            publisher.close();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            publisher.closeExceptionally(exception);
        } catch (IOException | RuntimeException exception) {
            publisher.closeExceptionally(exception);
        } finally {
            activeThreads.remove(readingThread);
        }
    }

    private static String dataPayload(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith(":")) {
            return null;
        }
        if (!trimmed.startsWith("data:")) {
            return null;
        }
        String payload = trimmed.substring("data:".length()).strip();
        return payload.isEmpty() ? null : payload;
    }

    private static String readErrorBody(Stream<String> body) {
        StringBuilder text = new StringBuilder();
        try (body) {
            body.takeWhile(ignored -> text.length() < ERROR_BODY_LIMIT).forEach(line -> {
                if (text.length() < ERROR_BODY_LIMIT) {
                    text.append(line).append('\n');
                }
            });
        }
        if (text.length() > ERROR_BODY_LIMIT) {
            text.setLength(ERROR_BODY_LIMIT);
        }
        return text.toString().strip();
    }
}
