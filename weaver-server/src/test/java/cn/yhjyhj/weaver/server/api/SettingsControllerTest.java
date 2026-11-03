package cn.yhjyhj.weaver.server.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.server.TestWorkspaceTempDirFactory;
import cn.yhjyhj.weaver.server.config.WeaverProperties;
import cn.yhjyhj.weaver.server.settings.ModelClientProvider;
import cn.yhjyhj.weaver.server.settings.ModelSettingsService;
import cn.yhjyhj.weaver.server.settings.ModelSettingsStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class SettingsControllerTest {

    private static final ObjectMapper JSON = new ObjectMapper(); // 从新增响应中读取配置 id

    @TempDir(factory = TestWorkspaceTempDirFactory.class)
    private Path tempDirectory; // 隔离每个 HTTP 测试的持久化文件

    private MockMvc mockMvc; // 不启动完整 Spring 上下文的 HTTP 入口
    private ModelSettingsService service; // 使用真实存储与业务逻辑

    @BeforeEach
    void setUp() {
        WeaverProperties properties = new WeaverProperties(
                Path.of("workspace"),
                50,
                Path.of("data", "weaver.db"),
                new WeaverProperties.ModelProperties(
                        "openai-chat-completions",
                        URI.create("https://env.example.com"),
                        "env-secret",
                        "env-model",
                        "system",
                        4096,
                        0.7,
                        120),
                new WeaverProperties.ApprovalProperties(300));
        ModelClient client = request -> {
            throw new UnsupportedOperationException();
        };
        service = new ModelSettingsService(
                new ModelSettingsStore(tempDirectory.resolve("config.json")),
                properties,
                ModelClientProvider.fixed(client));
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SettingsController(service))
                .setValidator(validator)
                .build();
    }

    @Test
    void listsMaskedSettingsModels() throws Exception {
        mockMvc.perform(get("/api/settings/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeId").value("env"))
                .andExpect(jsonPath("$.models[0].source").value("env"))
                .andExpect(jsonPath("$.models[0].hasKey").value(true))
                .andExpect(jsonPath("$.models[0].apiKey").doesNotExist())
                .andExpect(content().string(not(containsString("env-secret"))));
    }

    @Test
    void createsUpdatesActivatesAndDeletesModelsWithoutExposingKeys() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/settings/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "DeepSeek",
                                  "protocol": "openai-chat-completions",
                                  "baseUrl": "https://api.deepseek.com",
                                  "model": "deepseek-chat",
                                  "apiKey": "sk-secret"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.hasKey").value(true))
                .andExpect(jsonPath("$.source").value("settings"))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(content().string(not(containsString("sk-secret"))))
                .andReturn();
        String id = JSON.readTree(created.getResponse().getContentAsString())
                .path("id")
                .asText();

        mockMvc.perform(put("/api/settings/models/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "DeepSeek 2",
                                  "protocol": "openai-chat-completions",
                                  "baseUrl": "https://api.deepseek.com",
                                  "model": "deepseek-reasoner"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("DeepSeek 2"))
                .andExpect(jsonPath("$.hasKey").value(true))
                .andExpect(jsonPath("$.apiKey").doesNotExist());

        mockMvc.perform(post("/api/settings/models/{id}/activate", id))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/settings/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeId").value(id));

        mockMvc.perform(post("/api/settings/models/{id}/activate", "env"))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/settings/models/{id}", id))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/settings/models"))
                .andExpect(jsonPath("$.activeId").value("env"))
                .andExpect(jsonPath("$.models[0].source").value("env"));
    }

    @Test
    void rejectsUnsupportedProtocolAndInvalidBody() throws Exception {
        mockMvc.perform(post("/api/settings/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Unknown",
                                  "protocol": "unsupported",
                                  "baseUrl": "https://example.com",
                                  "model": "unknown"
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/settings/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": " ",
                                  "protocol": "openai-chat-completions",
                                  "baseUrl": "",
                                  "model": ""
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsNotFoundForUnknownModelAndConflictWhenDeletingActiveModel() throws Exception {
        mockMvc.perform(put("/api/settings/models/{id}", "missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Missing",
                                  "protocol": "openai-chat-completions",
                                  "baseUrl": "https://example.com",
                                  "model": "missing"
                                }
                                """))
                .andExpect(status().isNotFound());

        MvcResult created = mockMvc.perform(post("/api/settings/models")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "DeepSeek",
                                  "protocol": "openai-chat-completions",
                                  "baseUrl": "https://api.deepseek.com",
                                  "model": "deepseek-chat",
                                  "apiKey": "sk-secret"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String id = JSON.readTree(created.getResponse().getContentAsString())
                .path("id")
                .asText();
        mockMvc.perform(post("/api/settings/models/{id}/activate", id))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/settings/models/{id}", id))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/settings/models/{id}", "missing"))
                .andExpect(status().isNotFound());
    }
}
