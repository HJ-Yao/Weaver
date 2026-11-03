package cn.yhjyhj.weaver.server.settings;

import java.util.Objects;

/**
 * 用户持久化的单个模型连接配置。
 *
 * @param id 配置的唯一标识；创建后不可复用
 * @param name 面向用户展示的名称
 * @param protocol 模型协议标识
 * @param baseUrl API 基础地址
 * @param model 模型名称
 * @param apiKey API 密钥；只允许写入磁盘，不得进入设置视图
 */
public record ModelConfig(
        String id,
        String name,
        String protocol,
        String baseUrl,
        String model,
        String apiKey) {

    /** 校验持久化配置的必需字段；密钥允许为空。 */
    public ModelConfig {
        id = Objects.requireNonNull(id, "id");
        name = Objects.requireNonNull(name, "name");
        protocol = Objects.requireNonNull(protocol, "protocol");
        baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
        model = Objects.requireNonNull(model, "model");
    }

    /** 记录日志时不能把密钥带出去。 */
    @Override
    public String toString() {
        return "ModelConfig[id=%s, name=%s, protocol=%s, baseUrl=%s, model=%s, apiKey=***]"
                .formatted(id, name, protocol, baseUrl, model);
    }
}
