package cn.yhjyhj.weaver.core.loop.context;

import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;

/** 会话输入信箱：线程安全的待处理用户输入队列。 */
public final class Inbox {

    /** 按到达顺序保存待处理输入。 */
    private final LinkedBlockingQueue<String> inputs = new LinkedBlockingQueue<>();

    /** 追加一条待处理输入；空白输入被忽略。 */
    public void offer(String input) {
        Objects.requireNonNull(input, "input");
        if (!input.isBlank()) {
            inputs.offer(input);
        }
    }

    /** 取出最早的一条；队列为空时返回 null。 */
    public String poll() {
        return inputs.poll();
    }

    /** 是否还有待处理输入。 */
    public boolean isEmpty() {
        return inputs.isEmpty();
    }
}
