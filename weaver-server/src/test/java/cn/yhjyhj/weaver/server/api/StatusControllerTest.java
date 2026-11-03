package cn.yhjyhj.weaver.server.api;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.yhjyhj.weaver.server.config.WeaverProperties;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StatusControllerTest {

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
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new StatusController(properties))
                .build();

        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.storage").value("sqlite"))
                .andExpect(jsonPath("$.wal").value(true))
                .andExpect(jsonPath("$.workspace").isNotEmpty())
                .andExpect(jsonPath("$.maxSteps").value(37))
                .andExpect(jsonPath("$.uptimeSeconds").value(greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.model").value("deepseek-reasoner"))
                .andExpect(jsonPath("$.protocol").value("openai-chat-completions"));
    }
}
