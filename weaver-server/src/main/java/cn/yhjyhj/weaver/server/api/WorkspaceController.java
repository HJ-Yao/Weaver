package cn.yhjyhj.weaver.server.api;

import cn.yhjyhj.weaver.server.api.dto.WorkspaceDiff;
import cn.yhjyhj.weaver.server.api.dto.WorkspaceDirectory;
import cn.yhjyhj.weaver.server.api.dto.WorkspaceEntry;
import cn.yhjyhj.weaver.server.service.SessionService;
import cn.yhjyhj.weaver.server.workspace.WorkspaceDiffService;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 暴露工作区只读状态与目录浏览。
 *
 * <p>目录浏览仅供本机控制台使用，不做路径白名单或额外权限校验。
 */
@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    private static final int ERROR_SUMMARY_LIMIT = 500; // 对外错误信息上限

    private final WorkspaceDiffService diffService; // 工作区 git diff 读取服务
    private final SessionService sessions; // 会话工作目录来源
    private final Path defaultWorkspace; // 无会话或会话未知时使用的目录

    /**
     * 创建工作区 HTTP 控制器。
     *
     * @param diffService 工作区 diff 服务
     * @param sessions 会话服务
     * @param defaultWorkspace 默认工作区
     */
    public WorkspaceController(
            WorkspaceDiffService diffService,
            SessionService sessions,
            @Qualifier("weaverDefaultWorkspace") Path defaultWorkspace) {
        this.diffService = Objects.requireNonNull(diffService, "diffService");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.defaultWorkspace = Objects.requireNonNull(defaultWorkspace, "defaultWorkspace")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * 返回指定会话工作区中未提交改动的结构化 diff。
     *
     * <p>省略或不存在的会话使用默认工作区，以保持旧调用方兼容。
     *
     * @param sessionId 可选会话标识
     * @return 文件与 diff 行；超过安全上限时标记截断
     * @throws ResponseStatusException git diff 不可用时返回 500
     */
    @GetMapping("/diff")
    public WorkspaceDiff diff(
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        Path root = sessionId == null || sessionId.isBlank()
                ? defaultWorkspace
                : sessions.workspace(sessionId).orElse(defaultWorkspace);
        try {
            return diffService.diff(root);
        } catch (IllegalStateException error) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, summarize(error.getMessage()), error);
        }
    }

    /**
     * 浏览一个本机目录的直接子目录。
     *
     * @param rawPath 绝对路径；省略时从用户主目录开始
     * @return 当前目录、父目录、子目录和文件系统根
     * @throws ResponseStatusException 路径非法、不存在、不是目录或不可读时返回 400
     */
    @GetMapping("/browse")
    public WorkspaceDirectory browse(
            @RequestParam(value = "path", required = false) String rawPath) {
        Path current = resolveBrowsePath(rawPath);
        if (!Files.exists(current) || !Files.isDirectory(current) || !Files.isReadable(current)) {
            throw badRequest("workspace path must be a readable directory");
        }

        try {
            current = current.toRealPath();
        } catch (IOException error) {
            throw badRequest("workspace path is not accessible");
        }

        List<WorkspaceEntry> directories = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(current)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry) && Files.isReadable(entry)) {
                    Path normalized = entry.toAbsolutePath().normalize();
                    directories.add(new WorkspaceEntry(
                            normalized.getFileName().toString(), normalized.toString()));
                }
            }
        } catch (IOException | DirectoryIteratorException error) {
            throw badRequest("failed to read workspace directory");
        }
        directories.sort(Comparator.comparing(
                WorkspaceEntry::name, String.CASE_INSENSITIVE_ORDER));

        Path parent = current.getParent();
        return new WorkspaceDirectory(
                current.toString(),
                parent == null ? null : parent.toString(),
                directories,
                roots());
    }

    private static Path resolveBrowsePath(String rawPath) {
        String value = rawPath == null || rawPath.isBlank()
                ? System.getProperty("user.home")
                : rawPath;
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute()) {
                throw badRequest("workspace path must be absolute");
            }
            return path.normalize();
        } catch (InvalidPathException error) {
            throw badRequest("invalid workspace path");
        }
    }

    private static List<String> roots() {
        return Arrays.stream(File.listRoots())
                .map(File::toPath)
                .map(path -> path.toAbsolutePath().normalize().toString())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
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
