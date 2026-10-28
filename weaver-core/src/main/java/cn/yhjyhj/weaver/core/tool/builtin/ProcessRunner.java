package cn.yhjyhj.weaver.core.tool.builtin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** 以固定工作目录、超时和输出上限执行外部命令。 */
final class ProcessRunner {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30L); // 工具默认执行上限
    static final int DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024; // 工具默认输出上限

    private static final String TRUNCATION_MARKER = "\n...[truncated]"; // 输出截断提示
    private static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(5L); // 清理后等待管道关闭
    // 只匹配明确的凭据名；避免把 GIT_AUTHOR_* 这类正常变量误删。
    private static final Pattern SENSITIVE_ENVIRONMENT = Pattern.compile(
            "(?i).*(api[_-]?key|token|secret|password|credential|authorization).*");

    private final Duration timeout; // 单次命令执行上限
    private final int maxOutputBytes; // 合并输出允许保留的最大字节数
    private final OutputCapture outputCapture; // 从合并输出流读取内容，测试可替换故障读取

    /** 创建使用 30 秒和 64KB 默认限制的执行器。 */
    ProcessRunner() {
        this(
                DEFAULT_TIMEOUT,
                DEFAULT_MAX_OUTPUT_BYTES,
                input -> capture(input, DEFAULT_MAX_OUTPUT_BYTES));
    }

    /**
     * 创建指定限制的执行器，供测试缩短超时。
     *
     * @param timeout 单次命令执行上限
     * @param maxOutputBytes 合并输出上限
     */
    ProcessRunner(Duration timeout, int maxOutputBytes) {
        this(timeout, maxOutputBytes, input -> capture(input, maxOutputBytes));
    }

    /**
     * 创建指定限制和输出读取实现的执行器。
     *
     * @param timeout 单次命令执行上限
     * @param maxOutputBytes 合并输出上限
     * @param outputCapture 输出读取实现
     */
    ProcessRunner(Duration timeout, int maxOutputBytes, OutputCapture outputCapture) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.outputCapture = Objects.requireNonNull(outputCapture, "outputCapture");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxOutputBytes <= TRUNCATION_MARKER.getBytes(StandardCharsets.UTF_8).length) {
            throw new IllegalArgumentException("maxOutputBytes is too small");
        }
        this.maxOutputBytes = maxOutputBytes;
    }

    /**
     * 运行命令并合并标准输出与标准错误。
     *
     * @param workingDirectory 固定工作目录
     * @param command 逐项传入的命令参数
     * @return 合并输出、退出码、超时与读错状态
     * @throws IOException 进程无法启动时抛出
     * @throws InterruptedException 等待进程时线程被中断
     */
    Result run(Path workingDirectory, List<String> command)
            throws IOException, InterruptedException {
        return run(workingDirectory, command, Map.of());
    }

    /**
     * 使用附加环境变量运行命令并合并标准输出与标准错误。
     *
     * @param workingDirectory 固定工作目录
     * @param command 逐项传入的命令参数
     * @param additionalEnvironment 启动前注入的环境变量
     * @return 合并输出、退出码、超时与读错状态
     * @throws IOException 进程无法启动时抛出
     * @throws InterruptedException 等待进程时线程被中断
     */
    Result run(
            Path workingDirectory,
            List<String> command,
            Map<String, String> additionalEnvironment)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(List.copyOf(command))
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(additionalEnvironment);
        removeSensitiveEnvironment(builder.environment());
        Process process = builder.start();

        FutureTask<CapturedOutput> outputTask =
                new FutureTask<>(() -> outputCapture.capture(process.getInputStream()));
        Thread readerThread =
                Thread.ofVirtual().name("weaver-process-output").start(outputTask);
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                destroyProcessTree(process);
                CapturedOutput output = awaitOutput(outputTask);
                return new Result(
                        timeoutContent(output), -1, true, output.readError());
            }
            CapturedOutput output = awaitOutput(outputTask);
            int exitCode = output.readError() ? -1 : process.exitValue();
            return new Result(render(output), exitCode, false, output.readError());
        } catch (InterruptedException exception) {
            destroyProcessTree(process);
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            if (!outputTask.isDone()) {
                outputTask.cancel(true);
                readerThread.interrupt();
            }
            // 命令可能留下子进程，正常退出后也清理仍存活的后代。
            destroyProcessTree(process);
        }
    }

    /**
     * 从子进程环境中移除名称可能包含凭据的变量。
     *
     * @param environment 待清理的子进程环境变量
     */
    static void removeSensitiveEnvironment(Map<String, String> environment) {
        List<String> sensitiveNames = environment.keySet().stream()
                .filter(name -> SENSITIVE_ENVIRONMENT.matcher(name).matches())
                .toList();
        sensitiveNames.forEach(environment::remove);

        // GIT_CONFIG_KEY_n 会命中敏感名规则，必须同时移除同组的值和计数，
        // 否则 Git 会因配置组不完整而拒绝启动。
        List<String> gitConfigNames = environment.keySet().stream()
                .filter(name -> name.matches("(?i)GIT_CONFIG_(COUNT|KEY_\\d+|VALUE_\\d+)"))
                .toList();
        gitConfigNames.forEach(environment::remove);
    }

    private CapturedOutput awaitOutput(FutureTask<CapturedOutput> outputFuture)
            throws InterruptedException {
        try {
            return outputFuture.get(OUTPUT_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            return new CapturedOutput(
                    ("output read failed: " + cause.getMessage()).getBytes(StandardCharsets.UTF_8),
                    false,
                    true);
        } catch (TimeoutException exception) {
            outputFuture.cancel(true);
            return new CapturedOutput(
                    "output read timed out".getBytes(StandardCharsets.UTF_8),
                    false,
                    true);
        }
    }

    private static CapturedOutput capture(InputStream input, int maxOutputBytes)
            throws IOException {
        ByteArrayOutputStream kept = new ByteArrayOutputStream(maxOutputBytes);
        byte[] buffer = new byte[8192];
        long totalBytes = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            int remaining = maxOutputBytes - kept.size();
            if (remaining > 0) {
                kept.write(buffer, 0, Math.min(remaining, read));
            }
            totalBytes += read;
        }
        return new CapturedOutput(kept.toByteArray(), totalBytes > maxOutputBytes, false);
    }

    private String render(CapturedOutput output) {
        String text = new String(output.bytes(), StandardCharsets.UTF_8);
        if (output.readError()) {
            return text;
        }
        if (!output.truncated()) {
            return text;
        }

        int markerBytes = TRUNCATION_MARKER.getBytes(StandardCharsets.UTF_8).length;
        int requestedEnd = Math.min(output.bytes().length, maxOutputBytes - markerBytes);
        int safeEnd = completeUtf8Prefix(output.bytes(), requestedEnd);
        return new String(Arrays.copyOf(output.bytes(), safeEnd), StandardCharsets.UTF_8)
                + TRUNCATION_MARKER;
    }

    private String timeoutContent(CapturedOutput output) {
        String content = render(output);
        String marker = "[timeout after " + formatDuration(timeout) + "]";
        if (content.isEmpty()) {
            return marker;
        }
        String separator = content.endsWith("\n") || content.endsWith("\r")
                ? ""
                : System.lineSeparator();
        return content + separator + marker;
    }

    private static String formatDuration(Duration duration) {
        if (duration.toMillis() % 1000L == 0L) {
            return duration.toSeconds() + "s";
        }
        return duration.toMillis() + "ms";
    }

    private static int completeUtf8Prefix(byte[] bytes, int requestedEnd) {
        if (requestedEnd <= 0 || requestedEnd >= bytes.length) {
            return Math.max(0, Math.min(requestedEnd, bytes.length));
        }
        int sequenceStart = requestedEnd;
        while (sequenceStart > 0 && (bytes[sequenceStart - 1] & 0xC0) == 0x80) {
            sequenceStart--;
        }
        int leadIndex = sequenceStart - 1;
        if (leadIndex < 0) {
            return requestedEnd;
        }
        int expectedLength = expectedUtf8Length(bytes[leadIndex] & 0xFF);
        return requestedEnd - leadIndex < expectedLength ? leadIndex : requestedEnd;
    }

    private static int expectedUtf8Length(int leadByte) {
        if ((leadByte & 0x80) == 0) {
            return 1;
        }
        if ((leadByte & 0xE0) == 0xC0) {
            return 2;
        }
        if ((leadByte & 0xF0) == 0xE0) {
            return 3;
        }
        if ((leadByte & 0xF8) == 0xF0) {
            return 4;
        }
        return 1;
    }

    private static void destroyProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(OUTPUT_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 一次进程执行的结果。
     *
     * @param content 合并后的输出或超时说明
     * @param exitCode 退出码；超时或读取输出失败时为 -1
     * @param timedOut 是否因超时终止
     * @param readError 是否因读取输出失败而终止
     */
    record Result(String content, int exitCode, boolean timedOut, boolean readError) {}

    record CapturedOutput(
            byte[] bytes, // 保留在输出上限内的前缀
            boolean truncated, // 原始输出是否被截断
            boolean readError // 读取输出时是否已发生错误
    ) {}

    /** 从子进程合并输出流读取内容。 */
    @FunctionalInterface
    interface OutputCapture {

        /**
         * 读取命令输出。
         *
         * @param input 子进程合并输出流
         * @return 受上限约束的输出快照
         * @throws IOException 读取失败时抛出
         */
        CapturedOutput capture(InputStream input) throws IOException;
    }
}
