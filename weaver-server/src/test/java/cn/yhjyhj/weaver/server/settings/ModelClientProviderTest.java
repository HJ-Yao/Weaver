package cn.yhjyhj.weaver.server.settings;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import cn.yhjyhj.weaver.server.config.WeaverProperties.ModelProperties;
import cn.yhjyhj.weaver.server.model.AnthropicMessagesModelClient;
import cn.yhjyhj.weaver.server.model.OpenAiChatCompletionsModelClient;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ModelClientProviderTest {

    @Test
    void rebuildsClientWhenProtocolChangesAndReusesUnchangedFingerprint() {
        ModelProperties defaults = new ModelProperties(
                "openai-chat-completions",
                URI.create("https://env.example.com"),
                "env-key",
                "env-model",
                "system",
                4096,
                0.7,
                120);
        AtomicReference<ActiveModel> active = new AtomicReference<>(active(
                "openai-chat-completions",
                "https://api.deepseek.com",
                "deepseek-chat"));
        DefaultModelClientProvider provider =
                new DefaultModelClientProvider(active::get, defaults);

        Object openAi = provider.current();
        assertInstanceOf(OpenAiChatCompletionsModelClient.class, openAi);
        assertSame(openAi, provider.current());
        provider.refresh();
        assertNotSame(openAi, provider.current());

        active.set(active(
                "anthropic-messages",
                "https://api.anthropic.com",
                "claude-sonnet"));

        assertInstanceOf(AnthropicMessagesModelClient.class, provider.current());
    }

    private static ActiveModel active(String protocol, String baseUrl, String model) {
        return new ActiveModel(
                "model-1",
                "Configured",
                protocol,
                baseUrl,
                model,
                "sk-secret");
    }
}
