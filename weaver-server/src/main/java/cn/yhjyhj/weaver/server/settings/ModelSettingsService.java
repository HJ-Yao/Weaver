package cn.yhjyhj.weaver.server.settings;

import cn.yhjyhj.weaver.server.config.WeaverProperties;
import cn.yhjyhj.weaver.server.config.WeaverProperties.ModelProperties;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 管理持久化模型配置、环境配置回退与运行时客户端刷新。 */
public class ModelSettingsService {

    /** 当前持久化模型设置。 */
    private final ModelSettingsStore store;

    /** 环境模型配置，作为无持久化激活项时的兜底。 */
    private final WeaverProperties properties;

    /** 模型客户端缓存；所有写操作后必须刷新。 */
    private final ModelClientProvider modelClientProvider;

    /**
     * 创建模型设置服务。
     *
     * @param store 模型设置文件存储
     * @param properties 环境模型配置
     * @param modelClientProvider 运行时模型客户端 provider
     */
    public ModelSettingsService(
            ModelSettingsStore store,
            WeaverProperties properties,
            ModelClientProvider modelClientProvider) {
        this.store = Objects.requireNonNull(store, "store");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.modelClientProvider =
                Objects.requireNonNull(modelClientProvider, "modelClientProvider");
    }

    /**
     * 返回脱敏后的设置视图。
     *
     * @return 有持久化项时返回持久化列表；完全无持久化项时返回 env 合成项
     */
    public synchronized SettingsView view() {
        ModelSettings settings = store.load();
        boolean hasActive = settings.models().stream()
                .anyMatch(model -> model.id().equals(settings.activeModelId()));

        List<SettingsView.ModelView> models =
                new ArrayList<>(settings.models().stream()
                        .map(SettingsView.ModelView::fromSettings)
                        .toList());
        // env 始终作为可选项出现，否则切回环境配置后界面就失去这个入口。
        models.add(SettingsView.ModelView.fromEnv(properties.model()));

        return new SettingsView(hasActive ? settings.activeModelId() : "env", models);
    }

    /**
     * 新增模型配置但不自动激活。
     *
     * @param request 新模型配置
     * @return 已写入磁盘的配置
     */
    public synchronized ModelConfig create(CreateModelRequest request) {
        Objects.requireNonNull(request, "request");
        validateRequest(
                request.name(), request.protocol(), request.baseUrl(), request.model());
        ModelConfig created = new ModelConfig(
                UUID.randomUUID().toString(),
                request.name(),
                request.protocol(),
                request.baseUrl(),
                request.model(),
                request.apiKey());
        ModelSettings settings = store.load();
        List<ModelConfig> models = new ArrayList<>(settings.models());
        models.add(created);
        saveAndRefresh(new ModelSettings(settings.activeModelId(), models));
        return created;
    }

    /**
     * 更新持久化模型配置；空密钥保留原密钥。
     *
     * @param id 持久化模型 id
     * @param request 更新后的展示与连接字段
     * @return 更新后的配置
     */
    public synchronized ModelConfig update(String id, UpdateModelRequest request) {
        Objects.requireNonNull(request, "request");
        validateRequest(
                request.name(), request.protocol(), request.baseUrl(), request.model());
        ModelSettings settings = store.load();
        int index = indexOf(settings.models(), id);
        if (index < 0) {
            throw new ModelNotFoundException(id);
        }
        ModelConfig existing = settings.models().get(index);
        String apiKey = request.apiKey() == null || request.apiKey().isBlank()
                ? existing.apiKey()
                : request.apiKey();
        ModelConfig updated = new ModelConfig(
                existing.id(),
                request.name(),
                request.protocol(),
                request.baseUrl(),
                request.model(),
                apiKey);
        List<ModelConfig> models = new ArrayList<>(settings.models());
        models.set(index, updated);
        saveAndRefresh(new ModelSettings(settings.activeModelId(), models));
        return updated;
    }

    /**
     * 激活持久化模型，或使用 {@code env} 回退到环境配置。
     *
     * @param id 持久化模型 id 或 {@code env}
     * @throws IllegalArgumentException 持久化 id 不存在
     */
    public synchronized void activate(String id) {
        ModelSettings settings = store.load();
        if ("env".equals(id)) {
            saveAndRefresh(new ModelSettings(null, settings.models()));
            return;
        }
        if (indexOf(settings.models(), id) < 0) {
            throw new IllegalArgumentException("model not found: " + id);
        }
        saveAndRefresh(new ModelSettings(id, settings.models()));
    }

    /**
     * 删除持久化模型；当前激活项不可删除。
     *
     * @param id 持久化模型 id
     * @throws ModelNotFoundException 配置不存在
     * @throws ActiveModelConflictException 配置正在激活
     */
    public synchronized void delete(String id) {
        ModelSettings settings = store.load();
        int index = indexOf(settings.models(), id);
        if (index < 0) {
            throw new ModelNotFoundException(id);
        }
        if (id.equals(settings.activeModelId())) {
            throw new ActiveModelConflictException(id);
        }
        List<ModelConfig> models = new ArrayList<>(settings.models());
        models.remove(index);
        saveAndRefresh(new ModelSettings(settings.activeModelId(), models));
    }

    /**
     * 解析本轮应使用的模型连接。
     *
     * @return 激活的持久化模型；没有有效激活项时返回环境模型
     */
    public ActiveModel activeModel() {
        return resolveActive(store.load(), properties.model());
    }

    /**
     * 对任意设置快照应用统一的激活与回退规则。
     *
     * @param settings 设置快照
     * @param envModel 环境模型
     * @return 有效激活模型或 env 模型
     */
    public static ActiveModel resolveActive(
            ModelSettings settings, ModelProperties envModel) {
        if (settings.models().isEmpty()) {
            return envActive(envModel);
        }
        return settings.models().stream()
                .filter(model -> model.id().equals(settings.activeModelId()))
                .findFirst()
                .map(model -> new ActiveModel(
                        model.id(),
                        model.name(),
                        model.protocol(),
                        model.baseUrl(),
                        model.model(),
                        model.apiKey()))
                .orElseGet(() -> envActive(envModel));
    }

    private static ActiveModel envActive(ModelProperties envModel) {
        return new ActiveModel(
                "env",
                envModel.model(),
                envModel.protocol(),
                envModel.baseUrl().toString(),
                envModel.model(),
                envModel.apiKey());
    }

    private void saveAndRefresh(ModelSettings settings) {
        store.save(settings);
        modelClientProvider.refresh();
    }

    private static int indexOf(List<ModelConfig> models, String id) {
        for (int index = 0; index < models.size(); index++) {
            if (models.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    private static void validateRequest(
            String name, String protocol, String baseUrl, String model) {
        if (isBlank(name) || isBlank(baseUrl) || isBlank(model)) {
            throw new IllegalArgumentException("model name, baseUrl and model are required");
        }
        if (!"openai-chat-completions".equals(protocol)
                && !"anthropic-messages".equals(protocol)) {
            throw new IllegalArgumentException("unsupported model protocol: " + protocol);
        }
        try {
            URI parsed = URI.create(baseUrl);
            if (parsed.getScheme() == null || parsed.getHost() == null) {
                throw new IllegalArgumentException("baseUrl must be an absolute HTTP URL");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("baseUrl must be an absolute HTTP URL", exception);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 新增模型请求。
     *
     * @param name 展示名称
     * @param protocol 模型协议
     * @param baseUrl API 基础地址
     * @param model 模型名称
     * @param apiKey API 密钥；可为空
     */
    public record CreateModelRequest(
            @NotBlank String name,
            @NotBlank String protocol,
            @NotBlank String baseUrl,
            @NotBlank String model,
            String apiKey) {}

    /**
     * 更新模型请求；空密钥表示保留原值。
     *
     * @param name 展示名称
     * @param protocol 模型协议
     * @param baseUrl API 基础地址
     * @param model 模型名称
     * @param apiKey 新密钥；null 或空字符串表示保留
     */
    public record UpdateModelRequest(
            @NotBlank String name,
            @NotBlank String protocol,
            @NotBlank String baseUrl,
            @NotBlank String model,
            String apiKey) {}

    /** 目标持久化模型不存在。 */
    public static final class ModelNotFoundException extends RuntimeException {

        /**
         * 创建不存在异常。
         *
         * @param id 缺失的模型 id
         */
        public ModelNotFoundException(String id) {
            super("model not found: " + id);
        }
    }

    /** 激活中的模型不能删除。 */
    public static final class ActiveModelConflictException extends RuntimeException {

        /**
         * 创建激活冲突异常。
         *
         * @param id 正在激活的模型 id
         */
        public ActiveModelConflictException(String id) {
            super("active model cannot be deleted: " + id);
        }
    }
}
