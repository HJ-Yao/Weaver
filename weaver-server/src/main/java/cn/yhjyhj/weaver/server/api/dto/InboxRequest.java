package cn.yhjyhj.weaver.server.api.dto;

import cn.yhjyhj.weaver.core.loop.context.Inbox;
import jakarta.validation.constraints.NotBlank;

/**
 * 运行中向会话信箱投递输入的请求。
 *
 * @param content 用户输入；不能为空或仅含空白
 * @param target 生效目标；省略时默认为下一轮
 */
public record InboxRequest(@NotBlank String content, Inbox.Target target) {

    /** 将省略的目标规范化为 {@link Inbox.Target#TURN}。 */
    public InboxRequest {
        if (target == null) {
            target = Inbox.Target.TURN;
        }
    }
}
