package cn.yhjyhj.weaver.server.api;

import cn.yhjyhj.weaver.server.api.dto.ApprovalDecisionRequest;
import cn.yhjyhj.weaver.server.service.RunService;
import cn.yhjyhj.weaver.server.service.SessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 接收前端对 SSE 审批请求的 HTTP 决策。 */
@RestController
@RequestMapping("/api/sessions")
public class ApprovalController {

    private final SessionService sessions; // 会话存在性边界
    private final RunService runService; // 活动审批闸门入口

    /**
     * 创建审批决策控制器。
     *
     * @param sessions 会话服务
     * @param runService 运行服务
     */
    public ApprovalController(SessionService sessions, RunService runService) {
        this.sessions = sessions;
        this.runService = runService;
    }

    /**
     * 提交一次审批决策并唤醒等待中的运行线程。
     *
     * @param sessionId 会话标识
     * @param callId 工具调用标识
     * @param request 用户决策
     * @return 决策被接受时为 204
     * @throws ResponseStatusException 会话或待审批项不存在时返回 404
     */
    @PostMapping("/{sessionId}/approvals/{callId}")
    public ResponseEntity<Void> resolve(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("callId") String callId,
            @Valid @RequestBody ApprovalDecisionRequest request) {
        if (sessions.find(sessionId).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "session not found: " + sessionId);
        }
        if (!runService.resolveApproval(sessionId, callId, request.approved())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "approval not found: " + callId);
        }
        return ResponseEntity.noContent().build();
    }
}
