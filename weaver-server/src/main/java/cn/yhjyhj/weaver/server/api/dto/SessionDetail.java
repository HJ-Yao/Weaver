package cn.yhjyhj.weaver.server.api.dto;

import cn.yhjyhj.weaver.core.events.SessionSnapshot;

/**
 * 会话元数据与事件重放快照的组合视图。
 *
 * @param summary 会话元数据
 * @param snapshot 从事件事实源重放得到的当前状态
 */
public record SessionDetail(SessionSummary summary, SessionSnapshot snapshot) {}
