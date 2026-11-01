package cn.yhjyhj.weaver.core.loop.context;

/**
 * 运行取消信号：查询状态并注册一次性回调。
 */
public interface Cancellation {

    /**
     * 是否已请求取消。
     *
     * @return 已请求取消时返回 {@code true}
     */
    boolean isCancelled();

    /**
     * 注册取消回调。若此刻已取消，立即在当前线程执行一次。
     *
     * @param listener 取消时执行的动作
     */
    void onCancel(Runnable listener);

    /**
     * 永不取消的实现，供未接入取消的调用方使用。
     *
     * @return 永不取消的共享实例
     */
    static Cancellation never() {
        return NeverCancellation.INSTANCE;
    }
}

/** 不接收取消信号的包内共享实现。 */
enum NeverCancellation implements Cancellation {
    /** 进程内唯一实例。 */
    INSTANCE;

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public void onCancel(Runnable listener) {
        // 该实现没有取消来源，注册的动作永远不会被调用。
    }
}
