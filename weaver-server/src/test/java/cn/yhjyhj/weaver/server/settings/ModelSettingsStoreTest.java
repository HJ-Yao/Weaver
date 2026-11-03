package cn.yhjyhj.weaver.server.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.server.TestWorkspaceTempDirFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelSettingsStoreTest {

    @TempDir(factory = TestWorkspaceTempDirFactory.class)
    private Path tempDirectory; // 隔离每个测试的持久化文件

    @Test
    void savesAndLoadsModelSettingsRoundTrip() {
        Path configPath = tempDirectory.resolve(".weaver").resolve("config.json");
        ModelSettingsStore store = new ModelSettingsStore(configPath);
        ModelSettings expected = new ModelSettings(
                "model-1",
                List.of(new ModelConfig(
                        "model-1",
                        "DeepSeek",
                        "openai-chat-completions",
                        "https://api.deepseek.com",
                        "deepseek-chat",
                        "sk-secret")));

        store.save(expected);

        assertEquals(expected, store.load());
        assertTrue(Files.exists(configPath));
        assertEquals(
                expected,
                new ModelSettingsStore(configPath).load(),
                "new store instances should observe persisted settings");
    }

    @Test
    void returnsEmptySettingsWhenConfigFileIsMissing() {
        ModelSettingsStore store =
                new ModelSettingsStore(tempDirectory.resolve("missing.json"));

        assertEquals(new ModelSettings(null, List.of()), store.load());
    }

    @Test
    void damagedJsonReturnsEmptySettingsWithoutOverwritingTheFile() throws IOException {
        Path configPath = tempDirectory.resolve("config.json");
        byte[] damaged = "{ definitely-not-json apiKey=sk-leaked".getBytes(StandardCharsets.UTF_8);
        Files.write(configPath, damaged);
        ModelSettingsStore store = new ModelSettingsStore(configPath);

        assertEquals(new ModelSettings(null, List.of()), store.load());
        assertEquals(
                new String(damaged, StandardCharsets.UTF_8),
                Files.readString(configPath, StandardCharsets.UTF_8));
    }
}
