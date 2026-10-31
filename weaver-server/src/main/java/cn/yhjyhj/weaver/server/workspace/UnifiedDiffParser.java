package cn.yhjyhj.weaver.server.workspace;

import cn.yhjyhj.weaver.server.api.dto.WorkspaceDiff;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 将 {@code git diff --no-color --unified=3} 输出解析为前端可直接渲染的结构。
 *
 * <p>解析器不执行 IO，并按文件数、单文件行数和总行数三个上限截断结果。
 */
public final class UnifiedDiffParser {

    private static final int MAX_FILES = 50; // 单次响应的文件上限
    private static final int MAX_LINES_PER_FILE = 500; // 单文件渲染行上限
    private static final int MAX_TOTAL_LINES = 2000; // 单次响应总行数上限
    private static final String DEV_NULL = "/dev/null"; // 新增或删除文件缺失侧占位符

    /**
     * 解析完整 diff 输出。
     *
     * @param output git diff 的完整标准输出
     * @return 结构化改动；空输入返回空文件列表
     */
    public WorkspaceDiff parse(String output) {
        Objects.requireNonNull(output, "output");

        List<FileBuilder> files = new ArrayList<>();
        FileBuilder current = null;
        boolean inHunk = false;
        boolean truncated = false;
        int totalLines = 0;

        for (String line : output.split("\\R", -1)) {
            if (line.startsWith("diff --git ")) {
                if (current != null) {
                    files.add(current);
                }

                if (files.size() >= MAX_FILES) {
                    // 文件已满后仍继续扫描，以便对后续任意 hunk 行可靠置位截断标记。
                    current = null;
                    truncated = true;
                } else {
                    current = new FileBuilder();
                    current.headerPath = pathFromDiffHeader(line);
                }
                inHunk = false;
                continue;
            }

            if (current == null) {
                continue;
            }

            if (!inHunk) {
                if (line.startsWith("+++ ")) {
                    current.plusPath = normalizePath(line.substring(4));
                } else if (line.startsWith("--- ")) {
                    current.minusPath = normalizePath(line.substring(4));
                } else if (line.startsWith("@@")) {
                    inHunk = true;
                }
                continue;
            }

            if (line.startsWith("@@")) {
                // 一个文件可以有多个 hunk；元信息与 hunk 头不进入前端行列表。
                continue;
            }
            // hunk 内的 `+` / `-` 都是内容前缀；内容本身以 `++` 或 `--` 开头时同样要保留。
            if (line.startsWith("\\")) {
                continue;
            }

            WorkspaceDiff.DiffLine diffLine = toDiffLine(line);
            if (diffLine == null) {
                continue;
            }

            if (current.lines.size() >= MAX_LINES_PER_FILE || totalLines >= MAX_TOTAL_LINES) {
                truncated = true;
                continue;
            }

            current.lines.add(diffLine);
            totalLines++;
            if ("add".equals(diffLine.kind())) {
                current.additions++;
            } else if ("del".equals(diffLine.kind())) {
                current.deletions++;
            }
        }

        if (current != null) {
            files.add(current);
        }

        List<WorkspaceDiff.FileDiff> result = files.stream()
                .filter(FileBuilder::hasPath)
                .map(FileBuilder::build)
                .toList();
        return new WorkspaceDiff(result, truncated);
    }

    private static WorkspaceDiff.DiffLine toDiffLine(String line) {
        if (line.startsWith("+")) {
            return new WorkspaceDiff.DiffLine("add", line);
        }
        if (line.startsWith("-")) {
            return new WorkspaceDiff.DiffLine("del", line);
        }
        if (line.startsWith(" ")) {
            return new WorkspaceDiff.DiffLine("context", line);
        }
        return null;
    }

    private static String normalizePath(String value) {
        String path = value;
        int timestamp = path.indexOf('\t');
        if (timestamp >= 0) {
            path = path.substring(0, timestamp);
        }
        if (DEV_NULL.equals(path)) {
            return null;
        }
        if (path.startsWith("a/") || path.startsWith("b/")) {
            return path.substring(2);
        }
        return path;
    }

    private static String pathFromDiffHeader(String line) {
        int newSide = line.lastIndexOf(" b/");
        if (newSide < 0) {
            newSide = line.lastIndexOf("\"b/");
        }
        if (newSide < 0) {
            return null;
        }

        String path = line.substring(newSide + 3);
        if (path.endsWith("\"")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.isEmpty() ? null : path;
    }

    private static final class FileBuilder {

        private String headerPath; // 无 ---/+++ 时用于二进制或纯重命名文件
        private String plusPath; // +++ 侧路径，正常优先使用
        private String minusPath; // --- 侧路径，+++ 为 /dev/null 时回退
        private int additions; // 当前文件保留的新增行数
        private int deletions; // 当前文件保留的删除行数
        private final List<WorkspaceDiff.DiffLine> lines = new ArrayList<>(); // 截断后的可见行

        private boolean hasPath() {
            return plusPath != null || minusPath != null || headerPath != null;
        }

        private WorkspaceDiff.FileDiff build() {
            String path = plusPath != null ? plusPath : minusPath != null ? minusPath : headerPath;
            return new WorkspaceDiff.FileDiff(path, additions, deletions, List.copyOf(lines));
        }
    }
}
