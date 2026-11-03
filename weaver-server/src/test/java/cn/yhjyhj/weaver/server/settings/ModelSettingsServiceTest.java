package cn.yhjyhj.weaver.server.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.server.TestWorkspaceTempDirFactory;
import cn.yhjyhj.weaver.server.config.WeaverProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelSettingsServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper(); // 校验视图不会序列化密钥

    @TempDir(factory = TestWorkspaceTempDirFactory.class)
    private Path tempDirectory; // 隔离模型配置文件

    private ModelSettingsStore store; // 测试使用的真实文件存储
    private WeaverProperties properties; // 环境模型兜底配置
    private ModelSettingsService service; // 被测服务

    @BeforeEach
    void setUp() {
        store = new ModelSettingsStore(tempDirectory.resolve("config.json"));
        properties = properties("env-model", "https://env.example.com", "env-key");
        service = new ModelSettingsService(store, properties, fixedProvider());
    }

    @Test
    void synthesizesEnvModelWhenNoPersistentSettingsExist() throws Exception {
        SettingsView view = service.view();

        assertEquals("env", view.activeId());
        assertEquals(1, view.models().size());
        SettingsView.ModelView model = view.models().getFirst();
        assertEquals("env", model.id());
        assertEquals("env-model", model.name());
        assertEquals("openai-chat-completions", model.protocol());
        assertEquals("https://env.example.com", model.baseUrl());
        assertEquals("env-model", model.model());
        assertTrue(model.hasKey());
        assertEquals("env", model.source());
        assertFalse(JSON.writeValueAsString(view).contains("env-key"));
        assertFalse(JSON.writeValueAsString(view).contains("apiKey"));
    }

    @Test
    void createsMaskedSettingsEntryWithoutActivatingIt() throws Exception {
        ModelConfig created = service.create(new ModelSettingsService.CreateModelRequest(
                "DeepSeek",
                "openai-chat-completions",
                "https://api.deepseek.com",
                "deepseek-chat",
                "sk-secret"));

        SettingsView view = service.view();
        SettingsView.ModelView model = view.models().getFirst();
        assertEquals(created.id(), model.id());
        // 没有持久化激活项时，视图回退到环境配置，并始终保留 env 选项。
        assertEquals("env", view.activeId());
        assertEquals("env", view.models().getLast().id());
        assertTrue(model.hasKey());
        assertEquals("settings", model.source());
        assertFalse(JSON.writeValueAsString(view).contains("sk-secret"));
        assertFalse(JSON.writeValueAsString(view).contains("apiKey"));
    }

    @Test
    void updateWithEmptyApiKeyPreservesStoredKey() {
        ModelConfig created = service.create(new ModelSettingsService.CreateModelRequest(
                "DeepSeek",
                "openai-chat-completions",
                "https://api.deepseek.com",
                "deepseek-chat",
                "sk-secret"));

        ModelConfig updated = service.update(
                created.id(),
                new ModelSettingsService.UpdateModelRequest(
                        "DeepSeek 2",
                        "openai-chat-completions",
                        "https://api.deepseek.com",
                        "deepseek-reasoner",
                        ""));

        assertEquals("DeepSeek 2", updated.name());
        assertEquals("deepseek-reasoner", updated.model());
        assertEquals("sk-secret", updated.apiKey());
        assertEquals("sk-secret", store.load().models().getFirst().apiKey());
    }

    @Test
    void activateChangesActiveModelAndEnvRestoresPropertiesFallback() {
        ModelConfig created = service.create(new ModelSettingsService.CreateModelRequest(
                "Anthropic",
                "anthropic-messages",
                "https://api.anthropic.com",
                "claude-sonnet",
                "sk-ant"));

        service.activate(created.id());

        ActiveModel active = service.activeModel();
        assertEquals(created.id(), active.id());
        assertEquals("anthropic-messages", active.protocol());
        assertEquals("claude-sonnet", active.model());
        assertNotEquals(properties.model().model(), active.model());

        service.activate("env");

        ActiveModel fallback = service.activeModel();
        assertEquals("env", fallback.id());
        assertEquals(properties.model().model(), fallback.model());
        assertEquals(properties.model().protocol(), fallback.protocol());
    }

    @Test
    void rejectsDeletingActiveEntryAndDeletingUnknownEntry() {
        ModelConfig created = service.create(new ModelSettingsService.CreateModelRequest(
                "DeepSeek",
                "openai-chat-completions",
                "https://api.deepseek.com",
                "deepseek-chat",
                "sk-secret"));
        service.activate(created.id());

        assertThrows(
                ModelSettingsService.ActiveModelConflictException.class,
                () -> service.delete(created.id()));
        assertThrows(
                ModelSettingsService.ModelNotFoundException.class,
                () -> service.delete("missing"));
    }

    @Test
    void rejectsActivatingUnknownPersistentModel() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.activate("missing"));
    }

    @Test
    void refreshesProviderAfterEveryMutation() {
        AtomicInteger refreshes = new AtomicInteger();
        ModelClientProvider recordingProvider = new ModelClientProvider() {
            @Override
            public ModelClient current() {
                return request -> {
                    throw new UnsupportedOperationException();
                };
            }

            @Override
            public void refresh() {
                refreshes.incrementAndGet();
            }
        };
        ModelSettingsService recordingService =
                new ModelSettingsService(store, properties, recordingProvider);

        ModelConfig created = recordingService.create(
                new ModelSettingsService.CreateModelRequest(
                        "DeepSeek",
                        "openai-chat-completions",
                        "https://api.deepseek.com",
                        "deepseek-chat",
                        "sk-secret"));
        recordingService.update(
                created.id(),
                new ModelSettingsService.UpdateModelRequest(
                        "DeepSeek 2",
                        "openai-chat-completions",
                        "https://api.deepseek.com",
                        "deepseek-reasoner",
                        null));
        recordingService.activate(created.id());
        recordingService.activate("env");
        recordingService.delete(created.id());

        assertEquals(5, refreshes.get());
    }

    @Test
    void unknownActiveIdFallsBackToEnvInView() {
        store.save(new ModelSettings(
                "missing",
                List.of(new ModelConfig(
                        "model-1",
                        "DeepSeek",
                        "openai-chat-completions",
                        "https://api.deepseek.com",
                        "deepseek-chat",
                        "sk-secret"))));

        SettingsView view = service.view();
        assertEquals("env", view.activeId());
        assertEquals("env", view.models().getLast().id());
        assertEquals("env-model", service.activeModel().model());
    }

    private static ModelClientProvider fixedProvider() {
        ModelClient client = request -> {
            throw new UnsupportedOperationException();
        };
        return ModelClientProvider.fixed(client);
    }

    private static WeaverProperties properties(String model, String baseUrl, String apiKey) {
        return new WeaverProperties(
                Path.of("workspace"),
                50,
                Path.of("data", "weaver.db"),
                new WeaverProperties.ModelProperties(
                        "openai-chat-completions",
                        URI.create(baseUrl),
                        apiKey,
                        model,
                        "system",
                        4096,
                        0.3,
                        30),
                new WeaverProperties.ApprovalProperties(300));
    }
}
