package cn.yhjyhj.weaver.server.api;

import cn.yhjyhj.weaver.server.api.dto.WorkspaceDiff;
import cn.yhjyhj.weaver.server.workspace.WorkspaceDiffService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 暴露工作区只读状态。 */
@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private static final int ERROR_SUMMARY_LIMIT = 500; // 对外错误信息上限

    private final WorkspaceDiffService diffService; // 工作区 git diff 读取服务

    /**
     * 创建工作区 HTTP 控制器。
     *
     * @param diffService 工作区 diff 服务
     */
    public WorkspaceController(WorkspaceDiffService diffService) {
        this.diffService = diffService;
    }

    /**
     * 返回当前工作区未提交改动的结构化 diff。
     *
     * @return 文件与 diff 行，超过安全上限时标记截断
     * @throws ResponseStatusException git diff 不可用时返回 500
     */
    @GetMapping("/diff")
    public WorkspaceDiff diff() {
        try {
            return diffService.diff();
        } catch (IllegalStateException error) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, summarize(error.getMessage()), error);
        }
    }

    private static String summarize(String message) {
        if (message == null || message.isBlank()) {
            return "workspace diff failed";
        }
        return message.length() <= ERROR_SUMMARY_LIMIT
                ? message
                : message.substring(0, ERROR_SUMMARY_LIMIT);
    }
}
