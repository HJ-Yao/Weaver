package cn.yhjyhj.weaver.server.settings;

import java.util.List;
import java.util.Objects;

/**
 * 设置页可安全展示的模型配置视图；结构中不存在 API 密钥字段。
 *
 * @param activeId 当前激活项 id；持久化 id 未知时为 null
 * @param models 与环境配置合并规则处理后的模型列表
 */
public record SettingsView(String activeId, List<ModelView> models) {

    /** 固定列表快照，避免响应序列化期间被并发修改。 */
    public SettingsView {
        models = models == null ? List.of() : List.copyOf(models);
    }

    /**
     * 单个模型的脱敏视图。
     *
     * @param id 设置项 id
     * @param name 展示名称
     * @param protocol 模型协议
     * @param baseUrl API 基础地址
     * @param model 模型名称
     * @param hasKey 是否已配置非空 API 密钥
     * @param source 配置来源：settings 或 env
     */
    public record ModelView(
            String id,
            String name,
            String protocol,
            String baseUrl,
            String model,
            boolean hasKey,
            String source) {

        /** 从环境配置创建脱敏视图；id 固定为 {@code env}。 */
        public static ModelView fromEnv(cn.yhjyhj.weaver.server.config.WeaverProperties.ModelProperties envModel) {
            return new ModelView(
                    "env",
                    envModel.model(),
                    envModel.protocol(),
                    envModel.baseUrl().toString(),
                    envModel.model(),
                    hasKey(envModel.apiKey()),
                    "env");
        }

        /** 从持久化配置创建脱敏视图。 */
        public static ModelView fromSettings(ModelConfig config) {
            Objects.requireNonNull(config, "config");
            return new ModelView(
                    config.id(),
                    config.name(),
                    config.protocol(),
                    config.baseUrl(),
                    config.model(),
                    hasKey(config.apiKey()),
                    "settings");
        }

        private static boolean hasKey(String apiKey) {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
