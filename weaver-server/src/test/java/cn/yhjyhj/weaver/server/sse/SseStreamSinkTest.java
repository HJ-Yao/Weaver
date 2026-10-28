package cn.yhjyhj.weaver.server.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.yhjyhj.weaver.core.model.StreamChunk;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SseStreamSinkTest {

    @Test
    void forwardsAllFourCallbackTypesWithTheirEventNames() {
        List<SentEvent> events = new ArrayList<>();
        SseStreamSink sink = new SseStreamSink(
                (eventName, data) -> events.add(new SentEvent(eventName, data)));
        StreamChunk.ToolCall call =
                new StreamChunk.ToolCall("call-1", "file", "{\"op\":\"read\"}");

        sink.onDelta("hello");
        sink.onToolCall(call);
        sink.onDone(StreamChunk.StopReason.STOP);
        sink.onError("boom");

        assertEquals(
                List.of(
                        new SentEvent("delta", "hello"),
                        new SentEvent("tool", call),
                        new SentEvent("done", StreamChunk.StopReason.STOP),
                        new SentEvent("error", "boom")),
                events);
    }

    @Test
    void convertsCheckedSendFailureIntoUncheckedIOException() {
        IOException failure = new IOException("client disconnected");
        SseStreamSink sink = new SseStreamSink(
                (eventName, data) -> {
                    throw failure;
                });

        UncheckedIOException thrown =
                assertThrows(UncheckedIOException.class, () -> sink.onDelta("hello"));

        assertEquals(failure, thrown.getCause());
    }

    @Test
    void serializesCallbacksFromConcurrentPublisherThreads() throws Exception {
        AtomicInteger activeSenders = new AtomicInteger();
        AtomicInteger maximumActiveSenders = new AtomicInteger();
        List<String> deltas = new ArrayList<>();
        SseStreamSink sink = new SseStreamSink(
                (eventName, data) -> {
                    int active = activeSenders.incrementAndGet();
                    maximumActiveSenders.accumulateAndGet(active, Math::max);
                    deltas.add(data.toString());
                    try {
                        Thread.sleep(1L);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("interrupted while sending SSE event");
                    }
                    activeSenders.decrementAndGet();
                });
        int workers = 8;
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int worker = 0; worker < workers; worker++) {
                int workerId = worker;
                executor.submit(() -> {
                    start.await();
                    for (int event = 0; event < 20; event++) {
                        sink.onDelta(workerId + ":" + event);
                    }
                    return null;
                });
            }
            start.countDown();
        }

        assertEquals(1, maximumActiveSenders.get());
        assertEquals(workers * 20, deltas.size());
    }

    private record SentEvent(String name, Object data) {}
}
