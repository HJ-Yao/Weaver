package cn.yhjyhj.weaver.server.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.yhjyhj.weaver.server.config.WeaverProperties.ModelProperties;
import cn.yhjyhj.weaver.server.model.AnthropicMessagesModelClient;
import cn.yhjyhj.weaver.server.model.OpenAiChatCompletionsModelClient;
import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WeaverConfigTest {

    @Test
    void createsClientForConfiguredOpenAiProtocol() {
        WeaverProperties properties = properties("openai-chat-completions");

        assertInstanceOf(
                OpenAiChatCompletionsModelClient.class,
                new WeaverConfig().modelClient(properties));
    }

    @Test
    void createsClientForConfiguredAnthropicMessagesProtocol() {
        WeaverProperties properties = properties("anthropic-messages");

        assertInstanceOf(
                AnthropicMessagesModelClient.class,
                new WeaverConfig().modelClient(properties));
    }

    @Test
    void rejectsUnsupportedProtocol() {
        WeaverProperties properties = properties("unsupported");

        assertThrows(
                IllegalArgumentException.class,
                () -> new WeaverConfig().modelClient(properties));
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
                        4096,
                        0.7,
                        5));
    }
}
