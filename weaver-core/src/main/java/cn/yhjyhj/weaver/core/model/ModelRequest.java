package cn.yhjyhj.weaver.core.model;

import cn.yhjyhj.weaver.core.events.SessionSnapshot;
import java.util.List;

/**
 * 一次模型调用的输入与历史快照。
 *
 * @param input 本次用户输入
 * @param history 调用开始时的会话消息快照
 */
public record ModelRequest(String input, List<SessionSnapshot.Message> history) {

    /**
     * 固定历史快照，避免模型调用期间被外部列表修改。
     *
     * @throws NullPointerException 当输入或历史为 null 时抛出
     */
    public ModelRequest {
        history = List.copyOf(history);
    }
}
