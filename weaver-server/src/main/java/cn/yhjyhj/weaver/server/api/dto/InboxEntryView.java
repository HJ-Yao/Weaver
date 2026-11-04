package cn.yhjyhj.weaver.server.api.dto;

import cn.yhjyhj.weaver.core.loop.context.Inbox;

/**
 * 待消费信箱条目的 HTTP/SSE 视图。
 *
 * @param id 条目标识
 * @param target 生效目标名称
 * @param content 原始输入文本
 */
public record InboxEntryView(String id, String target, String content) {

    /**
     * 将内部信箱条目转换为稳定的 API 视图。
     *
     * @param entry 待转换条目
     * @return 使用枚举名称表示目标的视图
     */
    public static InboxEntryView from(Inbox.Entry entry) {
        return new InboxEntryView(
                entry.id(), entry.target().name(), entry.content());
    }
}
