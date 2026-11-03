package cn.yhjyhj.weaver.server.settings;

import cn.yhjyhj.weaver.core.model.ModelClient;
import java.util.Objects;

/** 在每次运行前解析当前模型客户端，并允许设置变化后清空运行时缓存。 */
public interface ModelClientProvider {

    /**
     * 返回当前配置对应的模型客户端。
     *
     * @return 可立即用于一轮运行的模型客户端
     */
    ModelClient current();

    /** 清空缓存，使下一次访问重新解析配置。 */
    void refresh();

    /**
     * 测试与单次运行使用的固定实现。
     *
     * @param client 始终返回的模型客户端
     * @return 不感知配置变化的 provider
     */
    static ModelClientProvider fixed(ModelClient client) {
        ModelClient fixedClient = Objects.requireNonNull(client, "client");
        return new ModelClientProvider() {
            @Override
            public ModelClient current() {
                return fixedClient;
            }

            @Override
            public void refresh() {}
        };
    }
}
