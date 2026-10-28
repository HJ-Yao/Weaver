package cn.yhjyhj.weaver.server.api.dto;

import java.time.Instant;

/**
 * 会话列表中的元数据。
 *
 * @param id 会话唯一标识
 * @param title 会话标题
 * @param createdAt 会话创建时间
 */
public record SessionSummary(String id, String title, Instant createdAt) {}
