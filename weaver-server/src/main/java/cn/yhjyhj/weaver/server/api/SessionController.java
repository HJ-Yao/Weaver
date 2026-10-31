package cn.yhjyhj.weaver.server.api;

import cn.yhjyhj.weaver.server.api.dto.CreateSessionRequest;
import cn.yhjyhj.weaver.server.api.dto.SendMessageRequest;
import cn.yhjyhj.weaver.server.api.dto.SessionDetail;
import cn.yhjyhj.weaver.server.api.dto.SessionSummary;
import cn.yhjyhj.weaver.server.service.RunService;
import cn.yhjyhj.weaver.server.service.SessionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 暴露会话 CRUD 与流式消息 HTTP API。 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessions; // 会话元数据与快照服务
    private final RunService runService; // 流式 Agent 运行入口

    /**
     * 创建会话 HTTP 控制器。
     *
     * @param sessions 会话服务
     * @param runService 运行服务
     */
    public SessionController(SessionService sessions, RunService runService) {
        this.sessions = sessions;
        this.runService = runService;
    }

    /**
     * 创建会话。
     *
     * @param request 非空标题请求
     * @return 201 与新建会话元数据
     */
    @PostMapping
    public ResponseEntity<SessionSummary> create(
            @Valid @RequestBody CreateSessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(sessions.create(request.title()));
    }

    /**
     * 列出全部会话。
     *
     * @return 按创建时间升序排列的会话元数据
     */
    @GetMapping
    public List<SessionSummary> list() {
        return sessions.list();
    }

    /**
     * 返回单个会话的元数据和事件重放快照。
     *
     * @param id 会话标识
     * @return 会话详情
     * @throws ResponseStatusException 会话不存在时返回 404
     */
    @GetMapping("/{id}")
    public SessionDetail detail(@PathVariable("id") String id) {
        SessionSummary summary = requireSession(id);
        return new SessionDetail(summary, sessions.snapshot(id));
    }

    /**
     * 删除会话。
     *
     * @param id 会话标识
     * @return 204 无响应体
     * @throws ResponseStatusException 会话不存在时返回 404
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        if (!sessions.delete(id)) {
            throw notFound(id);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 向会话发送消息并以 SSE 返回运行过程。
     *
     * @param id 会话标识
     * @param request 非空消息内容
     * @return 声明 SSE 媒体类型的流式响应
     */
    @PostMapping(
            path = "/{id}/messages",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> sendMessage(
            @PathVariable("id") String id,
            @Valid @RequestBody SendMessageRequest request) {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(runService.run(id, request.content()));
    }

    /**
     * 请求中止会话当前运行。
     *
     * @param id 会话标识
     * @return 204 无响应体
     * @throws ResponseStatusException 会话不存在或没有活动运行时返回 404
     */
    @PostMapping("/{id}/run/cancel")
    public ResponseEntity<Void> cancel(@PathVariable("id") String id) {
        requireSession(id);
        if (!runService.cancel(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no active run");
        }
        return ResponseEntity.noContent().build();
    }

    private SessionSummary requireSession(String id) {
        return sessions.find(id).orElseThrow(() -> notFound(id));
    }

    private static ResponseStatusException notFound(String id) {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, "session not found: " + id);
    }
}
