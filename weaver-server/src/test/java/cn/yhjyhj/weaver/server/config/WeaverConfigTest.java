package cn.yhjyhj.weaver.server.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.yhjyhj.weaver.server.config.WeaverProperties.ModelProperties;
import cn.yhjyhj.weaver.server.model.AnthropicMessagesModelClient;
import cn.yhjyhj.weaver.server.model.OpenAiChatCompletionsModelClient;
import cn.yhjyhj.weaver.server.settings.ModelClientProvider;
import cn.yhjyhj.weaver.server.settings.ModelSettingsStore;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WeaverConfigTest {

    @Test
    void threeArgumentDefaultsProvideSystemPrompt() {
        WeaverProperties properties =
                new WeaverProperties(Path.of("."), 10, Path.of("target", "defaults.db"));

        assertFalse(properties.model().systemPrompt().isBlank());
    }

    @Test
    void createsClientForConfiguredOpenAiProtocol() {
        WeaverProperties properties = properties("openai-chat-completions");

        assertInstanceOf(
                OpenAiChatCompletionsModelClient.class,
                provider(properties).current());
    }

    @Test
    void createsClientForConfiguredAnthropicMessagesProtocol() {
        WeaverProperties properties = properties("anthropic-messages");

        assertInstanceOf(
                AnthropicMessagesModelClient.class,
                provider(properties).current());
    }

    @Test
    void rejectsUnsupportedProtocol() {
        WeaverProperties properties = properties("unsupported");

        assertThrows(
                IllegalArgumentException.class,
                () -> provider(properties).current());
    }

    private static ModelClientProvider provider(WeaverProperties properties) {
        return new WeaverConfig().modelClientProvider(
                new ModelSettingsStore(Path.of("target", "missing-config.json")),
                properties);
    }

    private static WeaverProperties properties(String protocol) {
        return new WeaverProperties(
                Path.of("."),
                10,
                Path.of("target", "config-test.db"),
                new ModelProperties(
                        protocol,
                        URI.create("https://api.deepseek.com"),
                        "test-key",
                        "deepseek-chat",
                        "system",
                        4096,
                        0.7,
                        5));
    }
}
