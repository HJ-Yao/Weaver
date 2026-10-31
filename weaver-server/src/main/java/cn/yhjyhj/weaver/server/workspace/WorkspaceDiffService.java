package cn.yhjyhj.weaver.server.workspace;

import cn.yhjyhj.weaver.server.api.dto.WorkspaceDiff;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 在配置的工作区中读取当前未提交改动。
 *
 * <p>服务只执行只读的 {@code git diff}；命令输出会先整体收取，再交给解析器裁剪。
 */
public final class WorkspaceDiffService {

    private static final long TIMEOUT_SECONDS = 10; // git diff 的最长等待时间
    private static final int ERROR_SUMMARY_LIMIT = 500; // 错误输出摘要上限

    private final Path workspace; // 执行 git 命令的工作区
    private final UnifiedDiffParser parser = new UnifiedDiffParser(); // 无状态 diff 解析器

    /**
     * 创建工作区改动读取服务。
     *
     * @param workspace 执行 git 命令的工作区路径
     */
    public WorkspaceDiffService(Path workspace) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
    }

    /**
     * 读取并解析工作区的未提交改动。
     *
     * @return 当前未提交改动的结构化快照
     * @throws IllegalStateException git 无法启动、超时或返回非零退出码时抛出
     */
    public WorkspaceDiff diff() {
        Process process;
        try {
            process = new ProcessBuilder(
                            "git",
                            "--no-pager",
                            "diff",
                            "--no-color",
                            "--unified=3")
                    .directory(workspace.toFile())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException error) {
            throw new IllegalStateException("failed to start git diff: " + error.getMessage(), error);
        }

        // 独立线程持续消费输出，避免大 diff 填满管道后与 waitFor 互相阻塞。
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread reader = Thread.startVirtualThread(() -> readOutput(process, output, readFailure));

        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                reader.join(1_000);
                throw new IllegalStateException(
                        "git diff timed out after " + TIMEOUT_SECONDS + " seconds: "
                                + summarize(readCapturedOutput(output)));
            }

            reader.join(1_000);
            if (reader.isAlive()) {
                process.destroyForcibly();
                reader.interrupt();
                throw new IllegalStateException("timed out while collecting git diff output");
            }
            IOException outputError = readFailure.get();
            if (outputError != null) {
                throw new IllegalStateException(
                        "failed to read git diff output: " + outputError.getMessage(), outputError);
            }

            String combinedOutput = readCapturedOutput(output);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IllegalStateException(
                        "git diff failed with exit code " + exitCode + ": "
                                + summarize(combinedOutput));
            }
            return parser.parse(combinedOutput);
        } catch (InterruptedException error) {
            process.destroyForcibly();
            reader.interrupt();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("git diff was interrupted", error);
        }
    }

    private static void readOutput(
            Process process,
            ByteArrayOutputStream output,
            AtomicReference<IOException> readFailure) {
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[8_192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                // 超时后主线程会读取已捕获摘要，因此每次写入保持原子可见。
                synchronized (output) {
                    output.write(buffer, 0, count);
                }
            }
        } catch (IOException error) {
            readFailure.set(error);
        }
    }

    private static String readCapturedOutput(ByteArrayOutputStream output) {
        synchronized (output) {
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static String summarize(String output) {
        String summary = output.strip();
        if (summary.isEmpty()) {
            return "no error output";
        }
        return summary.length() <= ERROR_SUMMARY_LIMIT
                ? summary
                : summary.substring(0, ERROR_SUMMARY_LIMIT);
    }
}
