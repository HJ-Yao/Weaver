package cn.yhjyhj.weaver.server.api.dto;

import java.time.Instant;

/**
 * 会话列表中的元数据。
 *
 * @param id 会话唯一标识
 * @param title 会话标题
 * @param createdAt 会话创建时间
 * @param workspace 生效的工作目录绝对路径
 */
public record SessionSummary(String id, String title, Instant createdAt, String workspace) {

    /**
     * 为尚未感知工作目录的旧调用方创建摘要；工作目录留空，由服务层补齐默认值。
     *
     * @param id 会话唯一标识
     * @param title 会话标题
     * @param createdAt 会话创建时间
     */
    public SessionSummary(String id, String title, Instant createdAt) {
        this(id, title, createdAt, null);
    }
}
