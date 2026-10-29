package cn.yhjyhj.weaver.server.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpSseTransportTest {

    @Test
    void emitsOnlyTrimmedDataPayloads() throws Exception {
        String response = """
                : comment

                event: ignored
                data:
                data:   first
                data:second
                id: 7

                """;
        try (StubServer server = StubServer.start(200, response, 0);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            RecordingSubscriber subscriber = new RecordingSubscriber();

            transport.post(server.uri(), Map.of(), "{}").subscribe(subscriber);
            subscriber.await();

            assertEquals(
                    List.of("first", "second"),
                    subscriber.items,
                    () -> "unexpected transport error: " + subscriber.error.get());
            assertTrue(subscriber.completed);
        }
    }

    @Test
    void rejectsNonPositiveAndMissingTimeout() {
        assertThrows(NullPointerException.class, () -> new HttpSseTransport(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HttpSseTransport(Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new HttpSseTransport(Duration.ofSeconds(-1)));
    }

    @Test
    void reportsClosedTransportWithoutOpeningRequest() throws Exception {
        HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5));
        transport.close();
        RecordingSubscriber subscriber = new RecordingSubscriber();

        transport.post(URI.create("http://127.0.0.1:1/chat/completions"), Map.of(), "{}")
                .subscribe(subscriber);
        subscriber.await();

        assertEquals("SSE transport is closed", subscriber.error.get().getMessage());
    }

    @Test
    void reportsInvalidHeaderAsPublisherError() throws Exception {
        try (StubServer server = StubServer.start(200, "data: ignored\n\n", 0);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5))) {
            RecordingSubscriber subscriber = new RecordingSubscriber();

            transport.post(server.uri(), Map.of("Bad Header", "value"), "{}")
                    .subscribe(subscriber);
            subscriber.await();

            assertTrue(
                    subscriber.error.get() instanceof IllegalArgumentException,
                    () -> "unexpected header error: " + subscriber.error.get());
        }
    }

    @Test
    void cancelsDelayedRequestWhenSubscriptionIsCancelled() throws Exception {
        try (StubServer server = StubServer.start(200, "data: late\n\n", 500)) {
            HttpSseTransport transport = new HttpSseTransport(Duration.ofSeconds(5));
            CancelImmediatelySubscriber subscriber = new CancelImmediatelySubscriber();

            transport.post(server.uri(), Map.of(), "{}").subscribe(subscriber);
            subscriber.awaitNoCallback();
            transport.close();
        }
    }

    @Test
    void reportsTimeoutAsPublisherError() throws Exception {
        try (StubServer server = StubServer.start(200, "data: [DONE]\n\n", 1000);
                HttpSseTransport transport = new HttpSseTransport(Duration.ofMillis(150))) {
            RecordingSubscriber subscriber = new RecordingSubscriber();

            transport.post(server.uri(), Map.of(), "{}").subscribe(subscriber);
            subscriber.await();

            assertTrue(
                    subscriber.error.get() instanceof java.net.http.HttpTimeoutException,
                    () -> "unexpected timeout error: " + subscriber.error.get());
        }
    }

    private static final class RecordingSubscriber implements Flow.Subscriber<String> {

        private final List<String> items = new ArrayList<>(); // 收到的 data 负载
        private final CountDownLatch finished = new CountDownLatch(1); // 等待终止信号
        private final AtomicReference<Throwable> error = new AtomicReference<>(); // 终止错误
        private volatile boolean completed; // 是否正常结束

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(String item) {
            items.add(item);
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

    private static final class CancelImmediatelySubscriber implements Flow.Subscriber<String> {

        private final CountDownLatch callback = new CountDownLatch(1); // 取消后不应再有终止回调

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.cancel();
        }

        @Override
        public void onNext(String item) {
            callback.countDown();
        }

        @Override
        public void onError(Throwable throwable) {
            callback.countDown();
        }

        @Override
        public void onComplete() {
            callback.countDown();
        }

        private void awaitNoCallback() throws InterruptedException {
            assertFalse(callback.await(200, TimeUnit.MILLISECONDS));
        }
    }

    private static final class StubServer implements AutoCloseable {

        private final HttpServer server; // 本地 HTTP 端点
        private final int delayMillis; // 模拟超时的响应延迟

        private StubServer(HttpServer server, String responseBody, int delayMillis) {
            this.server = server;
            this.delayMillis = delayMillis;
            server.createContext("/chat/completions", exchange -> respond(exchange, responseBody));
        }

        private static StubServer start(int status, String responseBody, int delayMillis)
                throws IOException {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0), 0);
            StubServer stub = new StubServer(server, responseBody, delayMillis);
            server.start();
            return stub;
        }

        private void respond(HttpExchange exchange, String responseBody) throws IOException {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        private URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/chat/completions");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
