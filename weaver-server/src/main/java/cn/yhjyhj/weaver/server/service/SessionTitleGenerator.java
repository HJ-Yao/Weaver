package cn.yhjyhj.weaver.server.service;

import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.model.ModelRequest;
import cn.yhjyhj.weaver.core.model.StreamChunk;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 用一次小模型调用为会话起标题；任何失败都退化为用户首句摘要。 */
public final class SessionTitleGenerator {

    /** 模型标题清理后允许保留的最大字符数。 */
    private static final int MAX_TITLE_CODE_POINTS = 30;
    /** 退化摘要保留的首句字符数。 */
    private static final int FALLBACK_CODE_POINTS = 16;
    /** 无法从输入中提取任何文字时使用的非空标题。 */
    private static final String DEFAULT_TITLE = "新会话";
    /** 标题生成请求的固定提示词。 */
    private static final String PROMPT_TEMPLATE = """
            请为下面这段用户需求起一个简短标题，不超过 12 个字，只输出标题本身，不要引号、标点或解释。

            需求：%s""";
    /** 末尾可移除的标点。 */
    private static final String TRAILING_PUNCTUATION = ".,!?;:，。！？；：、…";

    private final Duration timeout; // 单次标题模型调用的硬超时

    /**
     * 创建会话标题生成器。
     *
     * @param timeout 单次模型调用的硬超时，不得为负
     */
    public SessionTitleGenerator(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
    }

    /**
     * 生成标题；永远不会返回空白。
     *
     * @param model 当前模型客户端
     * @param firstUserMessage 会话首条用户消息
     * @return 模型标题或首句摘要
     */
    public String generate(ModelClient model, String firstUserMessage) {
        String fallback = fallbackSummary(firstUserMessage);
        if (model == null) {
            return fallback;
        }

        StringBuilder output = new StringBuilder();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        AtomicBoolean failed = new AtomicBoolean();
        try {
            String prompt = PROMPT_TEMPLATE.formatted(String.valueOf(firstUserMessage));
            model.stream(new ModelRequest(prompt, List.of())).subscribe(
                    new Flow.Subscriber<>() {
                        @Override
                        public void onSubscribe(Flow.Subscription value) {
                            subscription.set(value);
                            value.request(Long.MAX_VALUE);
                        }

                        @Override
                        public void onNext(StreamChunk chunk) {
                            if (chunk instanceof StreamChunk.Text text) {
                                output.append(text.delta());
                            } else if (chunk instanceof StreamChunk.Error) {
                                failed.set(true);
                                completed.countDown();
                            } else if (chunk instanceof StreamChunk.Done) {
                                completed.countDown();
                            }
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            failed.set(true);
                            completed.countDown();
                        }

                        @Override
                        public void onComplete() {
                            completed.countDown();
                        }
                    });

            if (!completed.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                cancel(subscription.get());
                return fallback;
            }
            if (failed.get()) {
                return fallback;
            }

            String title = cleanTitle(output.toString());
            return title.isBlank() ? fallback : title;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cancel(subscription.get());
            return fallback;
        } catch (RuntimeException exception) {
            cancel(subscription.get());
            return fallback;
        }
    }

    private static void cancel(Flow.Subscription subscription) {
        if (subscription == null) {
            return;
        }
        try {
            subscription.cancel();
        } catch (RuntimeException exception) {
            // 标题生成是尽力而为的辅助路径，清理失败也不能影响主运行。
        }
    }

    private static String cleanTitle(String raw) {
        String title = collapseWhitespace(raw).strip();
        title = stripWrappingQuotes(title);
        title = stripTrailingPunctuation(title);
        title = stripWrappingQuotes(title).strip();
        return truncate(title, MAX_TITLE_CODE_POINTS, false);
    }

    private static String fallbackSummary(String firstUserMessage) {
        String summary = collapseWhitespace(firstUserMessage).strip();
        if (summary.isEmpty()) {
            return DEFAULT_TITLE;
        }
        return truncate(summary, FALLBACK_CODE_POINTS, true);
    }

    private static String collapseWhitespace(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        StringBuilder collapsed = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = !collapsed.isEmpty();
                continue;
            }
            if (pendingSpace) {
                collapsed.append(' ');
                pendingSpace = false;
            }
            collapsed.appendCodePoint(codePoint);
        }
        return collapsed.toString();
    }

    private static String stripWrappingQuotes(String value) {
        String title = value.strip();
        while (!title.isEmpty()) {
            int first = title.codePointAt(0);
            int last = title.codePointBefore(title.length());
            if (!isQuote(first) && !isQuote(last)) {
                break;
            }
            if (isQuote(first)) {
                title = title.substring(Character.charCount(first)).strip();
            }
            if (!title.isEmpty() && isQuote(title.codePointBefore(title.length()))) {
                title = title.substring(0, title.length() - 1).strip();
            }
        }
        return title;
    }

    private static String stripTrailingPunctuation(String value) {
        String title = value.strip();
        while (!title.isEmpty()
                && TRAILING_PUNCTUATION.indexOf(title.codePointBefore(title.length())) >= 0) {
            title = title.substring(0, title.length() - 1).strip();
        }
        return title;
    }

    private static boolean isQuote(int codePoint) {
        return codePoint == '"'
                || codePoint == '\''
                || codePoint == '`'
                || codePoint == '“'
                || codePoint == '”'
                || codePoint == '‘'
                || codePoint == '’'
                || codePoint == '「'
                || codePoint == '」'
                || codePoint == '『'
                || codePoint == '』';
    }

    private static String truncate(String value, int limit, boolean ellipsis) {
        if (value.codePointCount(0, value.length()) <= limit) {
            return value;
        }
        int end = value.offsetByCodePoints(0, limit);
        return value.substring(0, end) + (ellipsis ? "…" : "");
    }
}
