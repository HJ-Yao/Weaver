package cn.yhjyhj.weaver.server.settings;

import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.server.config.WeaverProperties.ModelProperties;
import cn.yhjyhj.weaver.server.model.AnthropicMessagesModelClient;
import cn.yhjyhj.weaver.server.model.OpenAiChatCompletionsModelClient;
import java.net.URI;
import java.util.Objects;
import java.util.function.Supplier;

/** 按当前激活配置创建客户端，并用完整运行时指纹复用未变化的实例。 */
public final class DefaultModelClientProvider implements ModelClientProvider {

    /** 提供当前激活模型；每次访问都重新解析持久化配置。 */
    private final Supplier<ActiveModel> activeModelSupplier;

    /** 环境配置中的系统提示与生成参数。 */
    private final ModelProperties generationDefaults;

    /** 最近一次创建的客户端及其配置指纹。 */
    private volatile CacheEntry cache;

    /**
     * 创建按配置指纹缓存的 provider。
     *
     * @param activeModelSupplier 当前激活模型解析器
     * @param generationDefaults 生成参数与超时兜底配置
     */
    public DefaultModelClientProvider(
            Supplier<ActiveModel> activeModelSupplier,
            ModelProperties generationDefaults) {
        this.activeModelSupplier =
                Objects.requireNonNull(activeModelSupplier, "activeModelSupplier");
        this.generationDefaults = Objects.requireNonNull(generationDefaults, "generationDefaults");
    }

    /**
     * 返回当前配置对应的客户端；指纹变化时自动重建。
     *
     * @return 按协议构造的模型客户端
     */
    @Override
    public ModelClient current() {
        ActiveModel active = Objects.requireNonNull(
                activeModelSupplier.get(), "activeModelSupplier returned null");
        ModelProperties properties = toProperties(active);
        Fingerprint fingerprint = Fingerprint.from(properties);
        CacheEntry currentCache = cache;
        if (currentCache != null && currentCache.fingerprint().equals(fingerprint)) {
            return currentCache.client();
        }

        synchronized (this) {
            currentCache = cache;
            if (currentCache != null && currentCache.fingerprint().equals(fingerprint)) {
                return currentCache.client();
            }
            ModelClient client = create(properties);
            cache = new CacheEntry(fingerprint, client);
            return client;
        }
    }

    /** 清空缓存；下一轮会重新读取当前设置。 */
    @Override
    public void refresh() {
        cache = null;
    }

    private ModelProperties toProperties(ActiveModel active) {
        return new ModelProperties(
                active.protocol(),
                URI.create(active.baseUrl()),
                active.apiKey(),
                active.model(),
                generationDefaults.systemPrompt(),
                generationDefaults.maxTokens(),
                generationDefaults.temperature(),
                generationDefaults.timeoutSeconds());
    }

    private static ModelClient create(ModelProperties properties) {
        return switch (properties.protocol()) {
            case "openai-chat-completions" ->
                    new OpenAiChatCompletionsModelClient(properties);
            case "anthropic-messages" ->
                    new AnthropicMessagesModelClient(properties);
            default -> throw new IllegalArgumentException(
                    "unsupported model protocol: " + properties.protocol());
        };
    }

    /** 生成客户端实例必须匹配的运行时配置。 */
    private record Fingerprint(
            String protocol,
            URI baseUrl,
            String model,
            String apiKey,
            String systemPrompt,
            Integer maxTokens,
            double temperature,
            int timeoutSeconds) {

        private static Fingerprint from(ModelProperties properties) {
            return new Fingerprint(
                    properties.protocol(),
                    properties.baseUrl(),
                    properties.model(),
                    properties.apiKey(),
                    properties.systemPrompt(),
                    properties.maxTokens(),
                    properties.temperature(),
                    properties.timeoutSeconds());
        }

        /** 记录日志时不能把密钥带出去。 */
        @Override
        public String toString() {
            return "Fingerprint[protocol=%s, baseUrl=%s, model=%s, apiKey=***, maxTokens=%s]"
                    .formatted(protocol, baseUrl, model, maxTokens);
        }
    }

    /** 一次成功解析得到的不可变缓存项。 */
    private record CacheEntry(Fingerprint fingerprint, ModelClient client) {}
}
