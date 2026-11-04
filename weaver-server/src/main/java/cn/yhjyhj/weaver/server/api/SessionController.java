package cn.yhjyhj.weaver.server.api;

import cn.yhjyhj.weaver.core.loop.context.Inbox;
import cn.yhjyhj.weaver.server.api.dto.CreateSessionRequest;
import cn.yhjyhj.weaver.server.api.dto.InboxContentRequest;
import cn.yhjyhj.weaver.server.api.dto.InboxEntryView;
import cn.yhjyhj.weaver.server.api.dto.InboxRequest;
import cn.yhjyhj.weaver.server.api.dto.RenameSessionRequest;
import cn.yhjyhj.weaver.server.api.dto.SendMessageRequest;
import cn.yhjyhj.weaver.server.api.dto.SessionDetail;
import cn.yhjyhj.weaver.server.api.dto.SessionEventView;
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
import org.springframework.web.bind.annotation.PatchMapping;
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
     * 返回会话的真实事件时间线。
     *
     * @param id 会话标识
     * @return 按 seq 升序排列的事件视图
     * @throws ResponseStatusException 会话不存在时返回 404
     */
    @GetMapping("/{id}/events")
    public List<SessionEventView> events(@PathVariable("id") String id) {
        requireSession(id);
        return SessionEventViewMapper.from(sessions.events(id));
    }

    /**
     * 修改会话标题。
     *
     * @param id 会话标识
     * @param request 非空且不超过 60 个字符的新标题
     * @return 更新后的会话元数据
     * @throws ResponseStatusException 会话不存在时返回 404
     */
    @PatchMapping("/{id}")
    public SessionSummary rename(
            @PathVariable("id") String id,
            @Valid @RequestBody RenameSessionRequest request) {
        if (!sessions.rename(id, request.title())) {
            throw notFound(id);
        }
        return requireSession(id);
    }

    /**
     * 删除会话及其全部事件。
     *
     * @param id 会话标识
     * @return 204 无响应体
     * @throws ResponseStatusException 会话不存在时返回 404；有活动运行时返回 409
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        requireSession(id);
        if (runService.isRunning(id)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "run already active: " + id);
        }
        if (!sessions.delete(id)) {
            throw notFound(id);
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 向会话发送消息并以 SSE 返回运行过程。
     *
     * <p>响应中的 {@code user_message} 会转发本轮首条输入以及运行中注入的用户消息。
     * 前端应以「内容与尚未确认的乐观消息相同则确认，否则追加」去重；assistant 文本只通过
     * {@code delta} 流式发送，不会再用 {@code user_message} 重复转发。
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
     * 向活动运行的信箱投递一条输入。
     *
     * <p>成功只表示条目已入队，不保证它已被当前模型消费。条目生效位置由 target 决定：
     * STEP 在当前轮下一步前注入，TURN 在下一轮开头处理。
     *
     * @param id 会话标识
     * @param request 非空白输入与可选目标
     * @return 202 与新建条目
     * @throws ResponseStatusException 会话不存在返回 404；没有活动运行返回 409；
     *     信箱已满返回 429
     */
    @PostMapping("/{id}/inbox")
    public ResponseEntity<InboxEntryView> enqueue(
            @PathVariable("id") String id,
            @Valid @RequestBody InboxRequest request) {
        Inbox.Entry entry =
                runService.enqueue(id, request.content(), request.target());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(InboxEntryView.from(entry));
    }

    /**
     * 返回活动运行当前未消费的信箱条目。
     *
     * @param id 会话标识
     * @return 按到达顺序排列的条目；没有活动运行或未挂接时为空数组
     * @throws ResponseStatusException 会话不存在时返回 404
     */
    @GetMapping("/{id}/inbox")
    public List<InboxEntryView> pending(@PathVariable("id") String id) {
        return runService.pending(id).stream()
                .map(InboxEntryView::from)
                .toList();
    }

    /**
     * 修改一条尚未消费的信箱条目。
     *
     * @param id 会话标识
     * @param entryId 条目标识
     * @param request 非空白的替换文本
     * @return 修改后的条目
     * @throws ResponseStatusException 会话或条目不存在返回 404；没有活动运行返回 409
     */
    @PatchMapping("/{id}/inbox/{entryId}")
    public InboxEntryView replace(
            @PathVariable("id") String id,
            @PathVariable("entryId") String entryId,
            @Valid @RequestBody InboxContentRequest request) {
        Inbox.Entry entry = runService.replacePending(id, entryId, request.content());
        if (entry == null) {
            throw inboxEntryNotFound(entryId);
        }
        return InboxEntryView.from(entry);
    }

    /**
     * 撤回一条尚未消费的信箱条目。
     *
     * @param id 会话标识
     * @param entryId 条目标识
     * @return 204 无响应体
     * @throws ResponseStatusException 会话或条目不存在返回 404；没有活动运行返回 409
     */
    @DeleteMapping("/{id}/inbox/{entryId}")
    public ResponseEntity<Void> removePending(
            @PathVariable("id") String id,
            @PathVariable("entryId") String entryId) {
        if (!runService.removePending(id, entryId)) {
            throw inboxEntryNotFound(entryId);
        }
        return ResponseEntity.noContent().build();
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

    private static ResponseStatusException inboxEntryNotFound(String entryId) {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, "inbox entry not found: " + entryId);
    }
}
