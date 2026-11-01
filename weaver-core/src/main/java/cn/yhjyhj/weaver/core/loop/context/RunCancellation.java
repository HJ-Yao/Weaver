package cn.yhjyhj.weaver.core.loop.context;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * 单次运行的线程安全取消信号。
 *
 * <p>取消是单向且幂等的；每个注册回调最多执行一次。回调执行失败只记录日志，
 * 不阻止其余回调继续收敛资源。
 */
@Slf4j
public final class RunCancellation implements Cancellation {

    /** 是否已经请求取消。 */
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /** 等待取消信号的注册回调。 */
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 请求取消；只有首次调用会触发已注册回调。
     */
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }

        // 先取快照再清空，避免回调执行期间新注册的动作与本次快照重复。
        List<Runnable> pending = List.copyOf(listeners);
        listeners.clear();
        pending.forEach(this::notifyListener);
    }

    @Override
    public void onCancel(Runnable listener) {
        Runnable registration =
                new OnceListener(Objects.requireNonNull(listener, "listener"));
        if (cancelled.get()) {
            notifyListener(registration);
            return;
        }

        listeners.add(registration);
        // cancel 可能恰好发生在首次检查与注册之间；由移除结果决定是否由当前线程补执行。
        if (cancelled.get() && listeners.remove(registration)) {
            notifyListener(registration);
        }
    }

    private void notifyListener(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException exception) {
            log.warn("cancellation listener failed", exception);
        }
    }

    /** 保证同一注册项在 cancel 与注册竞态下最多执行一次。 */
    private static final class OnceListener implements Runnable {

        /** 实际取消动作。 */
        private final Runnable delegate;

        /** 当前注册项是否已经执行。 */
        private final AtomicBoolean executed = new AtomicBoolean();

        private OnceListener(Runnable delegate) {
            this.delegate = delegate;
        }

        @Override
        public void run() {
            if (executed.compareAndSet(false, true)) {
                delegate.run();
            }
        }
    }
}
