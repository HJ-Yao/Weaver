package cn.yhjyhj.weaver.core.loop.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.loop.chain.StrategyHandler;
import cn.yhjyhj.weaver.core.loop.context.RunCancellation;
import cn.yhjyhj.weaver.core.loop.context.RunContext;
import cn.yhjyhj.weaver.core.loop.context.RunRequest;
import cn.yhjyhj.weaver.core.loop.context.StepResult;
import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.model.StreamSink;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReasonNodeTest {

    @Test
    void cancellationCancelsSubscriptionAndReleasesBlockedStep() throws Exception {
        RunCancellation cancellation = new RunCancellation();
        CountDownLatch subscribed = new CountDownLatch(1);
        AtomicBoolean subscriptionCancelled = new AtomicBoolean();
        AtomicInteger requested = new AtomicInteger();
        AtomicReference<Flow.Subscriber<? super StreamChunk>> subscriber = new AtomicReference<>();
        ModelClient model = request -> downstream -> {
            subscriber.set(downstream);
            downstream.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {
                    requested.incrementAndGet();
                }

                @Override
                public void cancel() {
                    subscriptionCancelled.set(true);
                }
            });
            subscribed.countDown();
        };
        StrategyHandler<RunRequest, RunContext, StepResult> terminal =
                (request, context) ->
                        new StepResult(false, false, context.getResponseText());
        ReasonNode node =
                new ReasonNode(model, ToolRegistry.empty(), terminal, terminal);
        AtomicInteger deltas = new AtomicInteger();
        StreamSink streamSink = new StreamSink() {
            @Override
            public void onDelta(String delta) {
                deltas.incrementAndGet();
            }

            @Override
            public void onToolCall(StreamChunk.ToolCall call) {}

            @Override
            public void onDone(StreamChunk.StopReason reason) {}
        };
        RunContext context =
                new RunContext("session", event -> {}, streamSink, cancellation);

        CompletableFuture<StepResult> result = CompletableFuture.supplyAsync(() -> {
            try {
                return node.apply(new RunRequest("session", "hello", 0), context);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
        assertTrue(subscribed.await(1, TimeUnit.SECONDS));

        cancellation.cancel();

        StepResult step = result.get(Duration.ofSeconds(1).toMillis(), TimeUnit.MILLISECONDS);
        assertTrue(subscriptionCancelled.get());
        assertFalse(step.aborted());
        assertEquals(1, requested.get());

        subscriber.get().onNext(new StreamChunk.Text("late"));

        assertEquals("", context.getResponseText());
        assertEquals(0, deltas.get());
        assertEquals(1, requested.get());
    }
}
