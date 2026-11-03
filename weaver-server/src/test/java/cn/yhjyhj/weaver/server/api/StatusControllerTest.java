package cn.yhjyhj.weaver.server.api;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.server.TestWorkspaceTempDirFactory;
import cn.yhjyhj.weaver.server.config.WeaverProperties;
import cn.yhjyhj.weaver.server.settings.ModelConfig;
import cn.yhjyhj.weaver.server.settings.ModelClientProvider;
import cn.yhjyhj.weaver.server.settings.ModelSettingsService;
import cn.yhjyhj.weaver.server.settings.ModelSettingsStore;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StatusControllerTest {

    @TempDir(factory = TestWorkspaceTempDirFactory.class)
    private Path tempDirectory; // 隔离激活模型配置

    @Test
    void returnsRuntimeStorageAndConfigurationStatus() throws Exception {
        WeaverProperties properties = new WeaverProperties(
                Path.of("workspace"),
                37,
                Path.of("data", "weaver.db"),
                new WeaverProperties.ModelProperties(
                        "openai-chat-completions",
                        URI.create("https://example.com"),
                        "",
                        "deepseek-reasoner",
                        "test prompt",
                        null,
                        0.7,
                        120));
        ModelSettingsService settings = settings(properties);
        ModelConfig created = settings.create(new ModelSettingsService.CreateModelRequest(
                "Anthropic",
                "anthropic-messages",
                "https://api.anthropic.com",
                "claude-sonnet",
                "sk-ant"));
        settings.activate(created.id());
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new StatusController(properties, settings))
                .build();

        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.storage").value("sqlite"))
                .andExpect(jsonPath("$.wal").value(true))
                .andExpect(jsonPath("$.workspace").isNotEmpty())
                .andExpect(jsonPath("$.maxSteps").value(37))
                .andExpect(jsonPath("$.uptimeSeconds").value(greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.model").value("claude-sonnet"))
                .andExpect(jsonPath("$.protocol").value("anthropic-messages"));
    }

    private ModelSettingsService settings(WeaverProperties properties) {
        ModelClient client = request -> {
            throw new UnsupportedOperationException();
        };
        return new ModelSettingsService(
                new ModelSettingsStore(tempDirectory.resolve("config.json")),
                properties,
                ModelClientProvider.fixed(client));
    }
}
