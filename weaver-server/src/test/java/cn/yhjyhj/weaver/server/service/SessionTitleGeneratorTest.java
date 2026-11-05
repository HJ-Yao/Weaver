package cn.yhjyhj.weaver.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import java.time.Duration;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SessionTitleGeneratorTest {

    @Test
    void returnsGeneratedTextWithoutQuotesOrTrailingPunctuation() {
        SessionTitleGenerator generator = new SessionTitleGenerator(Duration.ofSeconds(1));
        ModelClient model = request -> subscriber -> {
            subscriber.onSubscribe(new ImmediateSubscription());
            subscriber.onNext(new StreamChunk.Text("  \"  会话   标题。 \"  "));
            subscriber.onNext(new StreamChunk.Done(StreamChunk.StopReason.STOP));
        };

        String title = generator.generate(model, "修复登录页");

        assertEquals("会话 标题", title);
    }

    @Test
    void fallsBackToCompressedFirstMessageWhenModelReturnsBlank() {
        SessionTitleGenerator generator = new SessionTitleGenerator(Duration.ofSeconds(1));
        ModelClient model = request -> subscriber -> {
            subscriber.onSubscribe(new ImmediateSubscription());
            subscriber.onNext(new StreamChunk.Text(" \n\t "));
            subscriber.onNext(new StreamChunk.Done(StreamChunk.StopReason.STOP));
        };

        String title = generator.generate(model, "  fix   the   login   page  ");

        assertEquals("fix the login pa…", title);
    }

    @Test
    void timesOutAndCancelsSubscriptionThenFallsBack() {
        SessionTitleGenerator generator =
                new SessionTitleGenerator(Duration.ofMillis(30));
        AtomicBoolean cancelled = new AtomicBoolean();
        ModelClient model = request -> subscriber -> subscriber.onSubscribe(
                new Flow.Subscription() {
                    @Override
                    public void request(long count) {
                        // 故意不发送终止分片，让生成器走硬超时路径。
                    }

                    @Override
                    public void cancel() {
                        cancelled.set(true);
                    }
                });

        String title = generator.generate(model, "  timeout   fallback  ");

        assertEquals("timeout fallback", title);
        assertTrue(cancelled.get());
    }

    @Test
    void fallsBackWhenModelThrows() {
        SessionTitleGenerator generator = new SessionTitleGenerator(Duration.ofSeconds(1));
        ModelClient model = request -> {
            throw new IllegalStateException("model unavailable");
        };

        String title = generator.generate(model, "  exception   fallback  ");

        assertEquals("exception fallba…", title);
    }

    @Test
    void fallsBackWhenModelIsNull() {
        SessionTitleGenerator generator = new SessionTitleGenerator(Duration.ofSeconds(1));

        String title = generator.generate(null, "  null   model  ");

        assertEquals("null model", title);
    }

    @Test
    void returnsDefaultTitleWhenModelAndMessageAreBlank() {
        SessionTitleGenerator generator = new SessionTitleGenerator(Duration.ofSeconds(1));
        ModelClient model = request -> subscriber -> {
            subscriber.onSubscribe(new ImmediateSubscription());
            subscriber.onNext(new StreamChunk.Text(" \n "));
            subscriber.onNext(new StreamChunk.Done(StreamChunk.StopReason.STOP));
        };

        String title = generator.generate(model, " \t ");

        assertEquals("新会话", title);
    }

    @Test
    void fallsBackWhenModelReportsErrorChunk() {
        SessionTitleGenerator generator = new SessionTitleGenerator(Duration.ofSeconds(1));
        ModelClient model = request -> subscriber -> {
            subscriber.onSubscribe(new ImmediateSubscription());
            subscriber.onNext(new StreamChunk.Text("partial"));
            subscriber.onNext(new StreamChunk.Error("upstream failed"));
        };

        String title = generator.generate(model, "error chunk fallback");

        assertEquals("error chunk fall…", title);
    }

    @Test
    void truncatesGeneratedTitlesToThirtyCharacters() {
        SessionTitleGenerator generator = new SessionTitleGenerator(Duration.ofSeconds(1));
        ModelClient model = request -> subscriber -> {
            subscriber.onSubscribe(new ImmediateSubscription());
            subscriber.onNext(new StreamChunk.Text("abcdefghijklmnopqrstuvwxyz1234567890"));
            subscriber.onNext(new StreamChunk.Done(StreamChunk.StopReason.STOP));
        };

        String title = generator.generate(model, "long title");

        assertEquals("abcdefghijklmnopqrstuvwxyz1234", title);
    }

    @Test
    void cancelFailureDuringTimeoutStillFallsBack() {
        SessionTitleGenerator generator =
                new SessionTitleGenerator(Duration.ofMillis(20));
        ModelClient model = request -> subscriber -> subscriber.onSubscribe(
                new Flow.Subscription() {
                    @Override
                    public void request(long count) {
                        // 保持在途状态，让生成器进入超时取消路径。
                    }

                    @Override
                    public void cancel() {
                        throw new IllegalStateException("cancel failed");
                    }
                });

        assertEquals("cancel fallback", generator.generate(model, "cancel fallback"));
    }

    private static final class ImmediateSubscription implements Flow.Subscription {

        @Override
        public void request(long count) {
            // 测试流在发布时同步发送，无需保留请求量。
        }

        @Override
        public void cancel() {
            // 同步完成的测试流没有后台任务需要取消。
        }
    }
}
