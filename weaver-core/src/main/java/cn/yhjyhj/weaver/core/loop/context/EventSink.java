package cn.yhjyhj.weaver.core.loop.context;

import cn.yhjyhj.weaver.core.events.SessionEvent;

/**
 * 会话事件出口：事件序号由下游存储分配。
 */
@FunctionalInterface
public interface EventSink {

    /**
     * 发布一个会话事件。
     *
     * @param event 待持久化的事实事件
     */
    void emit(SessionEvent event);
}
