package cn.yhjyhj.weaver.server.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Weaver 运行配置。
 *
 * @param workspace 内置工具允许访问的工作区根目录
 * @param maxSteps 单轮 Agent 运行允许执行的最大步数
 * @param storePath 会话事件 SQLite 数据库文件路径
 */
@Validated
@ConfigurationProperties(prefix = "weaver")
public record WeaverProperties(
        @NotNull Path workspace,
        @Min(1) int maxSteps,
        @NotNull Path storePath) {}
