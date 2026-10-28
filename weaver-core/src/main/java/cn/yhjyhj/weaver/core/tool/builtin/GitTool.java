package cn.yhjyhj.weaver.core.tool.builtin;

import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 在 workspace 根目录执行受限的 Git 读取与发布操作。 */
public final class GitTool extends JsonTool {

    private static final String DESCRIPTION = // 面向模型的工具用途说明
            "Run git read or write operations. status/diff/log are read-only; "
                    + "commit/push are irreversible.";
    private static final String PARAMETERS_SCHEMA = // 模型调用参数契约
            """
            {
              "type": "object",
              "properties": {
                "op": {
                  "type": "string",
                  "enum": ["status", "diff", "log", "commit", "push"]
                },
                "path": {"type": "string"},
                "limit": {"type": "integer"},
                "message": {"type": "string"}
              },
              "required": ["op"]
            }
            """;

    private final Path workspaceRoot; // Git 命令的固定工作目录
    private final ProcessRunner processRunner; // 统一执行超时、输出截断与进程清理

    /**
     * 创建使用 30 秒超时和 64KB 输出上限的 Git 工具。
     *
     * @param workspaceRoot Git 仓库工作区
     * @throws IllegalArgumentException 根目录不存在或不是目录时抛出
     */
    public GitTool(Path workspaceRoot) {
        this.workspaceRoot = normalizeRoot(workspaceRoot);
        this.processRunner = new ProcessRunner();
    }

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "git";
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
    protected ToolResult executeArguments(ObjectNode arguments) throws Exception {
        final List<String> command;
        try {
            command = command(arguments);
        } catch (WorkspacePaths.EscapingPathException exception) {
            return new ToolResult("path escapes workspace", true, 0L);
        } catch (LogLimitExceededException exception) {
            return new ToolResult(exception.getMessage(), true, 0L);
        }
        ProcessRunner.Result process = processRunner.run(
                workspaceRoot,
                command,
                Map.of("GIT_TERMINAL_PROMPT", "0"));
        boolean isError =
                process.timedOut() || process.readError() || process.exitCode() != 0;
        return new ToolResult(process.content(), isError, 0L);
    }

    @Override
    protected Reversibility declaredReversibility(ObjectNode arguments) {
        return switch (operation(arguments)) {
            case "status", "diff", "log" -> Reversibility.REVERSIBLE;
            case "commit", "push" -> Reversibility.IRREVERSIBLE;
            default -> throw new IllegalArgumentException("unsupported git operation");
        };
    }

    @Override
    protected boolean declaredParallelizable(ObjectNode arguments) {
        return switch (operation(arguments)) {
            case "status", "diff", "log" -> true;
            case "commit", "push" -> false;
            default -> throw new IllegalArgumentException("unsupported git operation");
        };
    }

    private List<String> command(ObjectNode arguments) throws IOException {
        String operation = operation(arguments);
        return switch (operation) {
            case "status" -> List.of("git", "status", "--short");
            case "diff" -> diffCommand(arguments);
            case "log" -> List.of(
                    "git", "log", "--oneline", "-n", Integer.toString(logLimit(arguments)));
            case "commit" -> List.of(
                    "git", "commit", "-m", commitMessage(arguments));
            case "push" -> pushCommand();
            default -> throw new IllegalArgumentException(
                    "unsupported git operation: " + operation);
        };
    }

    private List<String> diffCommand(ObjectNode arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of("git", "diff"));
        String path = optionalText(arguments, "path");
        if (path != null && !path.isBlank()) {
            Path target = WorkspacePaths.resolve(workspaceRoot, path);
            command.add("--");
            command.add(workspaceRoot.relativize(target).toString());
        }
        return command;
    }

    private static int logLimit(ObjectNode arguments) {
        JsonNode value = arguments.get("limit");
        if (value == null) {
            return 10;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("limit must be an integer");
        }
        int limit = value.intValue();
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (limit > 1000) {
            throw new LogLimitExceededException("limit must be <= 1000");
        }
        return limit;
    }

    /**
     * 返回固定的发布命令；目标始终是 origin 的当前 HEAD。
     *
     * @return 不含强制推送参数的命令
     */
    static List<String> pushCommand() {
        return List.of("git", "push", "origin", "HEAD");
    }

    private static String commitMessage(ObjectNode arguments) {
        String message = requiredText(arguments, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        return message;
    }

    private static String operation(ObjectNode arguments) {
        return requiredNonBlankText(arguments, "op");
    }

    private static Path normalizeRoot(Path root) {
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

    /** 内部信号：日志条数超过安全上限。 */
    private static final class LogLimitExceededException extends RuntimeException {

        private LogLimitExceededException(String message) {
            super(message);
        }
    }
}
