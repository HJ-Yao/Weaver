package cn.yhjyhj.weaver.mcp.transport;

import cn.yhjyhj.weaver.mcp.McpException;
import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 通过子进程 stdin/stdout 交换逐行 JSON-RPC 消息。 */
public final class StdioTransport implements McpTransport {

    static final int MAX_LINE_BYTES = 16 * 1024 * 1024; // 单条协议消息的 UTF-8 字节上限
    private static final int MAX_DIAGNOSTIC_BYTES = 8 * 1024; // stderr 尾部保留量
    private static final Duration EXIT_GRACE = Duration.ofSeconds(2); // 每阶段退出等待时间
    private final StdioServerCommand command; // 子进程启动配置
    private final Object writeLock = new Object(); // 防止并发消息交错
    private final StringBuilder diagnostics = new StringBuilder(); // 有界 stderr 尾部
    private final AtomicBoolean closed = new AtomicBoolean(); // 关闭回调幂等标志
    private volatile Process process; // 已启动的服务器进程
    private volatile BufferedWriter stdin; // 子进程标准输入
    private volatile Consumer<String> onMessage; // 入站消息接收器
    private volatile Consumer<Throwable> onClosed; // 通道关闭接收器

    /** 使用指定的命令配置创建尚未启动的 stdio 通道。 */
    public StdioTransport(StdioServerCommand command) {
        this.command = command;
    }

    @Override
    public void start(Consumer<String> messageHandler, Consumer<Throwable> closedHandler) {
        if (process != null || closed.get()) throw new McpException("stdio transport already started or closed");
        onMessage = messageHandler;
        onClosed = closedHandler;
        ProcessBuilder builder = new ProcessBuilder();
        if (command.cwd() != null) builder.directory(command.cwd().toFile());
        builder.environment().putAll(command.env());
        List<String> invocation = resolveCommand(command.command(), command.args(), System.getProperty("os.name"),
                builder.environment(), command.cwd());
        builder.command(invocation);
        try {
            process = builder.start();
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread.startVirtualThread(this::readStdout);
            Thread.startVirtualThread(this::readStderr);
            Thread.startVirtualThread(this::waitForExit);
        } catch (IOException exception) {
            signalClosed(exception);
            throw new McpException("failed to start MCP server: " + command.command(), exception);
        }
    }

    static List<String> resolveCommand(String executable, List<String> arguments, String osName,
            Map<String, String> environment, Path workingDirectory) {
        if (!osName.toLowerCase(Locale.ROOT).contains("win")) {
            List<String> direct = new ArrayList<>();
            direct.add(executable);
            direct.addAll(arguments);
            return direct;
        }
        Path input = Path.of(executable);
        String fileName = input.getFileName().toString();
        if (!hasExtension(fileName)) {
            String pathValue = environmentValue(environment, "PATH");
            String configuredExtensions = environmentValue(environment, "PATHEXT");
            String extensions = configuredExtensions == null ? ".COM;.EXE;.BAT;.CMD" : configuredExtensions;
            for (String directory : pathValue == null ? new String[0] : pathValue.split(";")) {
                for (String extension : extensions.split(";")) {
                    Path candidate = Path.of(directory).resolve(fileName + extension);
                    if (Files.isRegularFile(candidate)) return windowsInvocation(candidate, arguments);
                }
            }
            throw new McpException("command not found: " + executable);
        }
        if (!input.isAbsolute() && workingDirectory != null) input = workingDirectory.resolve(input);
        if (!Files.isRegularFile(input)) throw new McpException("command not found: " + executable);
        return windowsInvocation(input, arguments);
    }

    private static String environmentValue(Map<String, String> environment, String key) {
        return environment.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private static List<String> windowsInvocation(Path executable, List<String> arguments) {
        String extension = extension(executable.getFileName().toString());
        List<String> invocation = new ArrayList<>();
        if (extension.equalsIgnoreCase(".cmd") || extension.equalsIgnoreCase(".bat")) {
            if (arguments.stream().anyMatch(StdioTransport::containsCmdMetaCharacter)) {
                throw new McpException("unsafe argument for Windows command script");
            }
            invocation.add("cmd.exe");
            invocation.add("/d");
            invocation.add("/c");
            invocation.add(executable.toAbsolutePath().toString());
        } else {
            invocation.add(executable.toAbsolutePath().toString());
        }
        invocation.addAll(arguments);
        return invocation;
    }

    private static boolean hasExtension(String fileName) {
        return fileName.lastIndexOf('.') > 0;
    }

    private static String extension(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index < 0 ? "" : fileName.substring(index);
    }

    private static boolean containsCmdMetaCharacter(String argument) {
        return argument.chars().anyMatch(character -> "&|<>^%\"".indexOf(character) >= 0);
    }

    private void readStdout() {
        Throwable failure = null;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        // 逐字节扫描换行；显式包一层缓冲，不依赖 JDK 对进程流是否已缓冲的实现细节。
        try (var reader = new BufferedInputStream(process.getInputStream())) {
            int value;
            while ((value = reader.read()) != -1) {
                if (value == '\n') {
                    byte[] bytes = line.toByteArray();
                    int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                    onMessage.accept(new String(bytes, 0, length, StandardCharsets.UTF_8));
                    line.reset();
                } else {
                    if (line.size() >= MAX_LINE_BYTES) throw new McpException("MCP stdio message exceeds 16 MB");
                    line.write(value);
                }
            }
            if (line.size() > 0) failure = new McpException("MCP stdio closed with an incomplete JSON-RPC message");
        } catch (Throwable exception) {
            failure = exception;
        } finally {
            if (failure != null) {
                signalClosed(failure);
                Process current = process;
                if (current != null && current.isAlive()) closeProcess(current);
            }
        }
    }

    private void readStderr() {
        try (var input = process.getErrorStream()) {
            byte[] bytes = new byte[1024];
            int count;
            while ((count = input.read(bytes)) != -1) {
                synchronized (diagnostics) {
                    diagnostics.append(new String(bytes, 0, count, StandardCharsets.UTF_8));
                    int overflow = diagnostics.length() - MAX_DIAGNOSTIC_BYTES;
                    if (overflow > 0) {
                        diagnostics.delete(0, overflow);
                    }
                }
            }
        } catch (IOException ignored) {
            // 关闭过程中 stderr 流被打断不改变通道关闭原因。
        }
    }

    private void waitForExit() {
        try {
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                signalClosed(null);
            } else {
                signalClosed(new McpException(
                        "MCP server exited with code " + exitCode + diagnosticSuffix()));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            signalClosed(exception);
        }
    }

    @Override
    public void send(String json) {
        synchronized (writeLock) {
            if (closed.get() || stdin == null) throw new McpException("stdio transport is closed");
            try {
                stdin.write(json);
                stdin.newLine();
                stdin.flush();
            } catch (IOException exception) {
                throw new McpException("failed to write to MCP server", exception);
            }
        }
    }

    @Override
    public String diagnostics() {
        synchronized (diagnostics) {
            return diagnostics.toString();
        }
    }

    @Override
    public void close() {
        if (closed.get()) return;
        Process current = process;
        if (current == null) {
            signalClosed(null);
            return;
        }
        closeProcess(current);
        signalClosed(null);
    }

    private void closeProcess(Process current) {
        List<ProcessHandle> descendants = current.descendants().toList();
        try {
            current.getOutputStream().close();
        } catch (IOException ignored) {
            // 关闭 stdin 失败时仍继续终止进程树。
        }
        if (waitFor(current, EXIT_GRACE)) {
            terminateDescendants(descendants);
            return;
        }
        descendants.forEach(ProcessHandle::destroy);
        current.destroy();
        if (waitFor(current, EXIT_GRACE)) {
            forceLivingDescendants(descendants);
            return;
        }
        descendants.forEach(ProcessHandle::destroyForcibly);
        current.destroyForcibly();
    }

    private void terminateDescendants(List<ProcessHandle> descendants) {
        descendants.forEach(ProcessHandle::destroy);
        long deadline = System.nanoTime() + EXIT_GRACE.toNanos();
        while (descendants.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        forceLivingDescendants(descendants);
    }

    private void forceLivingDescendants(List<ProcessHandle> descendants) {
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    private boolean waitFor(Process current, Duration duration) {
        try {
            return current.waitFor(duration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String diagnosticSuffix() {
        String tail = diagnostics();
        return tail.isBlank() ? "" : "; stderr: " + tail;
    }

    private void signalClosed(Throwable cause) {
        if (closed.compareAndSet(false, true) && onClosed != null) onClosed.accept(cause);
    }
}
