package cn.yhjyhj.weaver.server.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/**
 * Weaver 运行配置。
 *
 * @param workspace 内置工具允许访问的工作区根目录
 * @param maxSteps 单轮 Agent 运行允许执行的最大步数
 * @param storePath 会话事件 SQLite 数据库文件路径
 * @param model 模型端点与生成参数
 */
@Validated
@ConfigurationProperties(prefix = "weaver")
public record WeaverProperties(
        @NotNull Path workspace,
        @Min(1) int maxSteps,
        @NotNull Path storePath,
        @Valid @NotNull ModelProperties model) {

    /** 固定使用规范构造器进行配置绑定，忽略测试兼容构造器。 */
    @ConstructorBinding
    public WeaverProperties {}

    /**
     * 为只关心工作区与会话存储的调用方提供兼容构造，模型配置使用应用默认值。
     *
     * @param workspace 内置工具允许访问的工作区根目录
     * @param maxSteps 单轮 Agent 运行允许执行的最大步数
     * @param storePath 会话事件 SQLite 数据库文件路径
     */
    public WeaverProperties(Path workspace, int maxSteps, Path storePath) {
        this(
                workspace,
                maxSteps,
                storePath,
                new ModelProperties(
                        "openai-chat-completions",
                        URI.create("https://api.deepseek.com"),
                        "",
                        "deepseek-chat",
                        4096,
                        0.7,
                        120));
    }

    /**
     * OpenAI Chat Completions 兼容端点的连接与生成配置。
     *
     * @param protocol 模型协议标识；当前支持 {@code openai-chat-completions}
     * @param baseUrl API 前缀；客户端会追加 {@code /chat/completions}
     * @param apiKey API 密钥；允许为空，首次请求时返回明确错误
     * @param model 请求使用的模型名
     * @param maxTokens 单次响应的最大输出 token 数
     * @param temperature 采样温度
     * @param timeoutSeconds HTTP 请求超时秒数
     */
    public record ModelProperties(
            @NotBlank String protocol,
            @NotNull URI baseUrl,
            String apiKey,
            @NotBlank String model,
            @Min(1) int maxTokens,
            double temperature,
            @Min(1) int timeoutSeconds) {}
}
