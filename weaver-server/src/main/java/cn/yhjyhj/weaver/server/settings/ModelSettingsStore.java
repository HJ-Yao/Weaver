package cn.yhjyhj.weaver.server.settings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/** 以 JSON 文件持久化模型设置，并保证替换过程不会留下半个配置文件。 */
@Slf4j
public class ModelSettingsStore {

    /** 正式配置文件的绝对路径。 */
    private final Path configPath;

    /** 与正式文件同目录的临时写入路径，确保可执行原子移动。 */
    private final Path temporaryPath;

    /** 模型设置的 JSON 编解码器。 */
    private final ObjectMapper objectMapper;

    /**
     * 使用默认 Jackson 配置创建文件存储。
     *
     * @param configPath 模型设置文件路径
     */
    public ModelSettingsStore(Path configPath) {
        this(configPath, new ObjectMapper());
    }

    /**
     * 使用指定 JSON 编解码器创建文件存储，供应用统一配置与测试注入。
     *
     * @param configPath 模型设置文件路径
     * @param objectMapper JSON 编解码器
     */
    public ModelSettingsStore(Path configPath, ObjectMapper objectMapper) {
        this.configPath = Objects.requireNonNull(configPath, "configPath").toAbsolutePath();
        this.temporaryPath = this.configPath.resolveSibling(
                this.configPath.getFileName() + ".tmp");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * 读取模型设置。
     *
     * <p>文件缺失、无法读取或 JSON 损坏时返回空配置；此方法不会改写原文件。
     *
     * @return 读取到的设置；无法读取时为空设置
     */
    public ModelSettings load() {
        if (!Files.isRegularFile(configPath)) {
            return new ModelSettings(null, java.util.List.of());
        }
        try {
            return objectMapper.readValue(Files.readString(configPath), ModelSettings.class);
        } catch (JsonProcessingException exception) {
            // 日志不能携带解析上下文，避免损坏文件中的密钥进入日志。
            log.warn(
                    "ignoring invalid model settings at {}; file will not be overwritten",
                    configPath);
            return new ModelSettings(null, java.util.List.of());
        } catch (IOException exception) {
            log.warn("unable to read model settings at {}; using empty settings", configPath);
            return new ModelSettings(null, java.util.List.of());
        }
    }

    /**
     * 原子保存完整模型设置。
     *
     * <p>先创建父目录并写入同目录临时文件，再以原子移动替换正式文件。POSIX 平台尽量把
     * 文件权限收紧为仅当前用户可读写。
     *
     * @param settings 要保存的完整快照
     */
    public synchronized void save(ModelSettings settings) {
        Objects.requireNonNull(settings, "settings");
        Path parent = configPath.getParent();
        try {
            Files.createDirectories(parent);
            Files.writeString(
                    temporaryPath,
                    objectMapper.writeValueAsString(settings),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            restrictPermissions(temporaryPath);
            Files.move(
                    temporaryPath,
                    configPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            deleteTemporaryFile();
            throw new UncheckedIOException("failed to save model settings", exception);
        }
    }

    private static void restrictPermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(
                    path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows 等非 POSIX 文件系统依赖用户目录 ACL。
        }
    }

    private void deleteTemporaryFile() {
        try {
            Files.deleteIfExists(temporaryPath);
        } catch (IOException ignored) {
            // 保留原始保存异常；残留临时文件不会覆盖正式配置。
        }
    }
}
