package cn.yhjyhj.weaver.core.events;

/** 落库形态：事件及其单调序号。seq 由 EventStore 分配。 */
public record StoredEvent(long seq, SessionEvent event) {}