package cn.yhjyhj.weaver.core.tool.builtin;

import cn.yhjyhj.weaver.core.events.ToolResult;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 在 workspace 根目录执行一条 shell 命令。 */
public final class ShellTool extends JsonTool {

    private static final String DESCRIPTION = // 面向模型的工具用途说明
            "Run a shell command in the workspace root. Commands are irreversible; "
                    + "dangerous commands require approval.";
    private static final String PARAMETERS_SCHEMA = // 模型调用参数契约
            """
            {
              "type": "object",
              "properties": {"cmd": {"type": "string"}},
              "required": ["cmd"]
            }
            """;

    private final Path workspaceRoot; // 命令的固定工作目录
    private final ProcessRunner processRunner; // 统一执行超时、输出截断与进程清理
    private final Map<String, String> additionalEnvironment; // 启动前注入并统一清理的测试环境

    /**
     * 创建使用 30 秒超时和 64KB 输出上限的 shell 工具。
     *
     * @param workspaceRoot 工作区根目录
     * @throws IllegalArgumentException 根目录不存在或不是目录时抛出
     */
    public ShellTool(Path workspaceRoot) {
        this(
                workspaceRoot,
                ProcessRunner.DEFAULT_TIMEOUT,
                ProcessRunner.DEFAULT_MAX_OUTPUT_BYTES,
                Map.of());
    }

    /**
     * 创建指定执行限制的 shell 工具。
     *
     * @param workspaceRoot 工作区根目录
     * @param timeout 单次命令执行上限
     * @param maxOutputBytes 合并输出上限
     */
    ShellTool(Path workspaceRoot, Duration timeout, int maxOutputBytes) {
        this(workspaceRoot, timeout, maxOutputBytes, Map.of());
    }

    /**
     * 创建指定执行限制和附加环境的 shell 工具，供环境隔离测试使用。
     *
     * @param workspaceRoot 工作区根目录
     * @param timeout 单次命令执行上限
     * @param maxOutputBytes 合并输出上限
     * @param additionalEnvironment 启动前注入的环境变量
     */
    ShellTool(
            Path workspaceRoot,
            Duration timeout,
            int maxOutputBytes,
            Map<String, String> additionalEnvironment) {
        this.workspaceRoot = normalizeRoot(workspaceRoot);
        this.processRunner = new ProcessRunner(timeout, maxOutputBytes);
        this.additionalEnvironment = Map.copyOf(additionalEnvironment);
    }

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "shell";
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
        String command = requiredNonBlankText(arguments, "cmd");
        ProcessRunner.Result process =
                processRunner.run(workspaceRoot, command(command), additionalEnvironment);
        boolean isError =
                process.timedOut() || process.readError() || process.exitCode() != 0;
        return new ToolResult(process.content(), isError, 0L);
    }

    @Override
    protected Reversibility declaredReversibility(ObjectNode arguments) {
        requiredNonBlankText(arguments, "cmd");
        return Reversibility.IRREVERSIBLE;
    }

    @Override
    protected boolean declaredParallelizable(ObjectNode arguments) {
        requiredNonBlankText(arguments, "cmd");
        return false;
    }

    private static List<String> command(String command) {
        String osName = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT);
        return osName.contains("win")
                ? List.of("cmd.exe", "/c", command)
                : List.of("sh", "-c", command);
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
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException(
                    "workspace root is not a directory: " + normalized, exception);
        }
    }
}
