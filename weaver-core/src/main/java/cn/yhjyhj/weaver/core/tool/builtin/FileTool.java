package cn.yhjyhj.weaver.core.tool.builtin;

import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** 在 workspace 根目录内读取、写入和列出文件。 */
public final class FileTool extends JsonTool {

    private static final String DESCRIPTION = // 面向模型的工具用途说明
            "Read, write or list files inside the workspace. write requires content; "
                    + "list returns relative paths.";
    private static final String PARAMETERS_SCHEMA = // 模型调用参数契约
            """
            {
              "type": "object",
              "properties": {
                "op": {"type": "string", "enum": ["read", "write", "list"]},
                "path": {"type": "string", "description": "相对工作区根的路径"},
                "content": {"type": "string", "description": "write 时的文件内容"}
              },
              "required": ["op", "path"]
            }
            """;

    static final long MAX_READ_BYTES = 1024 * 1024; // 单次读取允许的最大字节数
    static final int MAX_WRITE_BYTES = 1024 * 1024; // 单次写入允许的最大字节数
    static final int MAX_LIST_ENTRIES = 10_000; // 单次列出的最大条目数
    private static final String TRUNCATION_MARKER = "...[truncated]"; // 列表截断提示

    private final Path workspaceRoot; // 所有文件操作的真实工作区根目录
    private final int maxListEntries; // 单次列表操作允许返回的最大条目数

    /**
     * 创建限定在指定工作区内的文件工具。
     *
     * @param workspaceRoot 工作区根目录
     * @throws IllegalArgumentException 根目录不存在或不是目录时抛出
     */
    public FileTool(Path workspaceRoot) {
        this(workspaceRoot, MAX_LIST_ENTRIES);
    }

    /**
     * 创建指定列表上限的文件工具，供边界测试缩小条目规模。
     *
     * @param workspaceRoot 工作区根目录
     * @param maxListEntries 单次列表操作允许返回的最大条目数
     */
    FileTool(Path workspaceRoot, int maxListEntries) {
        this.workspaceRoot = realWorkspaceRoot(workspaceRoot);
        if (maxListEntries <= 0) {
            throw new IllegalArgumentException("maxListEntries must be positive");
        }
        this.maxListEntries = maxListEntries;
    }

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "file";
    }

    /** {@inheritDoc} */
    @Override
    public String description() {
        return DESCRIPTION;
    }

    /** {@inheritDoc} */
    @Override
    public String parametersSchema() {
        return PARAMETERS_SCHEMA;
    }

    @Override
    protected ToolResult executeArguments(ObjectNode arguments) throws IOException {
        String operation = requiredNonBlankText(arguments, "op");
        String path = requiredText(arguments, "path");
        Path target;
        try {
            target = resolveWorkspacePath(path);
        } catch (WorkspacePaths.EscapingPathException exception) {
            return new ToolResult("path escapes workspace", true, 0L);
        }

        return switch (operation) {
            case "read" -> read(target);
            case "write" -> write(target, requiredText(arguments, "content"));
            case "list" -> list(target);
            default -> throw new IllegalArgumentException(
                    "unsupported file operation: " + operation);
        };
    }

    @Override
    protected Reversibility declaredReversibility(ObjectNode arguments) {
        return switch (requiredNonBlankText(arguments, "op")) {
            case "read", "list" -> Reversibility.REVERSIBLE;
            case "write" -> Reversibility.RECOVERABLE;
            default -> throw new IllegalArgumentException("unsupported file operation");
        };
    }

    @Override
    protected boolean declaredParallelizable(ObjectNode arguments) {
        return switch (requiredNonBlankText(arguments, "op")) {
            case "read", "list" -> true;
            case "write" -> false;
            default -> throw new IllegalArgumentException("unsupported file operation");
        };
    }

    private static ToolResult read(Path target) throws IOException {
        long size = Files.size(target);
        if (size > MAX_READ_BYTES) {
            return new ToolResult("file too large: " + size + " bytes", true, 0L);
        }
        return new ToolResult(Files.readString(target), false, 0L);
    }

    private ToolResult write(Path target, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_WRITE_BYTES) {
            return new ToolResult("content too large", true, 0L);
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(target, bytes);
        return new ToolResult("wrote " + bytes.length + " bytes", false, 0L);
    }

    private ToolResult list(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            String relative = workspaceRoot.relativize(directory).toString();
            return new ToolResult("not a directory: " + relative, true, 0L);
        }
        List<String> entries;
        try (Stream<Path> paths = Files.list(directory)) {
            entries = paths
                    .map(workspaceRoot::relativize)
                    .map(Path::toString)
                    .sorted(Comparator.naturalOrder())
                    .limit((long) maxListEntries + 1L)
                    .toList();
        }
        boolean truncated = entries.size() > maxListEntries;
        String content = String.join(
                System.lineSeparator(),
                truncated ? entries.subList(0, maxListEntries) : entries);
        if (truncated) {
            content = content.isEmpty()
                    ? TRUNCATION_MARKER
                    : content + System.lineSeparator() + TRUNCATION_MARKER;
        }
        return new ToolResult(content, false, 0L);
    }

    private Path resolveWorkspacePath(String path) throws IOException {
        return WorkspacePaths.resolve(workspaceRoot, path);
    }

    private static Path realWorkspaceRoot(Path root) {
        Path normalized = Objects.requireNonNull(root, "workspaceRoot")
                .toAbsolutePath()
                .normalize();
        try {
            Path realRoot = normalized.toRealPath();
            if (!Files.isDirectory(realRoot)) {
                throw new IllegalArgumentException(
                        "workspace root is not a directory: " + realRoot);
            }
            return realRoot;
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "workspace root is not a directory: " + normalized, exception);
        }
    }
}
