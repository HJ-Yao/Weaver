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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * 基于 JDK HttpClient 的 SSE 传输层。
 *
 * <p>每次 {@link #post(URI, Map, String)} 都在虚拟线程中执行，使用
 * {@link SubmissionPublisher} 保留背压。传输层只向下游暴露 {@code data:} 行的负载，
 * 事件名、注释和空行由这里过滤。
 */
public final class HttpSseTransport implements AutoCloseable {

    private static final int ERROR_BODY_LIMIT = 4096; // 非 2xx 响应最多保留 4KB 诊断信息
    private static final AtomicLong REQUEST_IDS = new AtomicLong(); // 生成可辨认的虚拟线程名

    private final HttpClient httpClient; // 复用连接池的 JDK HTTP 客户端
    private final Duration timeout; // 建连与请求等待响应的统一超时
    private final Set<Thread> activeThreads = ConcurrentHashMap.newKeySet(); // 可被关闭打断的读取线程
    private final AtomicBoolean closed = new AtomicBoolean(); // 防止关闭后继续创建请求

    /**
     * 创建 SSE 传输层。
     *
     * @param timeout 单次请求超时；必须为正时长
     */
    public HttpSseTransport(Duration timeout) {
        this.timeout = requirePositive(timeout);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
    }

    /**
     * 发送 JSON POST，并返回逐行 SSE data 负载的 Publisher。
     *
     * <p>非 2xx 响应会以 {@link IllegalStateException} 终止流，错误信息包含最多
     * 4KB 响应体。订阅取消或传输层关闭时会中断对应的虚拟读取线程。
     *
     * @param uri 完整请求地址
     * @param headers 附加请求头
     * @param jsonBody JSON 请求体
     * @return 按背压消费的 SSE data 行流
     */
    public Flow.Publisher<String> post(
            URI uri,
            Map<String, String> headers,
            String jsonBody) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(jsonBody, "jsonBody");
        if (closed.get()) {
            return failedPublisher(new IllegalStateException("SSE transport is closed"));
        }

        return subscriber -> {
            Objects.requireNonNull(subscriber, "subscriber");
            SubmissionPublisher<String> publisher = new SubmissionPublisher<>();
            AtomicReference<Thread> worker = new AtomicReference<>();
            AtomicBoolean cancelled = new AtomicBoolean();
            Thread readingThread = Thread.ofVirtual()
                    .name("weaver-sse-" + REQUEST_IDS.incrementAndGet())
                    .unstarted(() -> readResponse(
                            uri, headers, jsonBody, publisher, worker, cancelled));
            worker.set(readingThread);
            publisher.subscribe(new Flow.Subscriber<>() {
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
                            readingThread.interrupt();
                        }
                    });
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
            });

            activeThreads.add(readingThread);
            readingThread.start();
        };
    }

    /**
     * 中断所有仍在读取响应的虚拟线程。
     */
    @Override
    public void close() {
        closed.set(true);
        activeThreads.forEach(Thread::interrupt);
    }

    private void readResponse(
            URI uri,
            Map<String, String> headers,
            String jsonBody,
            SubmissionPublisher<String> publisher,
            AtomicReference<Thread> worker,
            AtomicBoolean cancelled) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                    .timeout(timeout);
            headers.forEach(requestBuilder::header);
            HttpRequest request = requestBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<Stream<String>> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
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
                lines.map(HttpSseTransport::dataPayload)
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
            activeThreads.remove(worker.get());
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

    private static Flow.Publisher<String> failedPublisher(Throwable error) {
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

    private static Duration requirePositive(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.compareTo(Duration.ZERO) <= 0) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return timeout;
    }
}
