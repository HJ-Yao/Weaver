package cn.yhjyhj.weaver.server.config;

import cn.yhjyhj.weaver.core.approval.ApprovalGrantStore;
import cn.yhjyhj.weaver.core.approval.ApprovalPolicy;
import cn.yhjyhj.weaver.core.approval.InMemoryApprovalGrantStore;
import cn.yhjyhj.weaver.core.approval.RuleBasedApprovalPolicy;
import cn.yhjyhj.weaver.core.events.SessionEventStore;
import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import cn.yhjyhj.weaver.core.tool.builtin.FileTool;
import cn.yhjyhj.weaver.core.tool.builtin.GitTool;
import cn.yhjyhj.weaver.core.tool.builtin.ShellTool;
import cn.yhjyhj.weaver.server.model.AnthropicMessagesModelClient;
import cn.yhjyhj.weaver.server.model.OpenAiChatCompletionsModelClient;
import cn.yhjyhj.weaver.server.service.RunService;
import cn.yhjyhj.weaver.server.service.RunServiceDependencies;
import cn.yhjyhj.weaver.server.service.SessionService;
import cn.yhjyhj.weaver.server.store.SqliteEventStore;
import cn.yhjyhj.weaver.server.workspace.WorkspaceDiffService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配 Weaver 的进程级基础设施；单次运行对象由运行服务创建。 */
@Configuration
public class WeaverConfig {

    /**
     * 创建由 Weaver 配置指定路径的 SQLite 会话事件事实源。
     *
     * @param properties Weaver 运行配置
     * @return 可持久化、可回放的 SQLite 事件存储
     */
    @Bean(destroyMethod = "close")
    public SessionEventStore eventStore(WeaverProperties properties) {
        return new SqliteEventStore(properties.storePath());
    }

    /**
     * 创建限定在工作区内的内置工具注册表。
     *
     * @param properties Weaver 运行配置
     * @return 包含 file、shell、git 的工具注册表
     */
    @Bean
    public ToolRegistry toolRegistry(WeaverProperties properties) {
        Path root = properties.workspace();
        return new ToolRegistry(List.of(
                new FileTool(root),
                new ShellTool(root),
                new GitTool(root)));
    }

    /**
     * 创建只读取 Weaver 工作区未提交改动的服务。
     *
     * @param properties Weaver 运行配置
     * @return 绑定到配置工作区的 diff 服务
     */
    @Bean
    public WorkspaceDiffService workspaceDiffService(WeaverProperties properties) {
        return new WorkspaceDiffService(properties.workspace());
    }

    /**
     * 创建默认规则审批策略。
     *
     * @return 按工具回滚性修正的内置策略
     */
    @Bean
    public ApprovalPolicy approvalPolicy() {
        return RuleBasedApprovalPolicy.defaults();
    }

    /**
     * 创建进程内会话授权记录。
     *
     * @return 线程安全的内存授权存储
     */
    @Bean
    public ApprovalGrantStore approvalGrantStore() {
        return new InMemoryApprovalGrantStore();
    }

    /**
     * 使用进程级依赖与应用配置创建运行服务。
     *
     * @param sessions 会话元数据服务
     * @param eventStore 会话事件事实源
     * @param model 模型客户端
     * @param tools 工具注册表
     * @param approvalPolicy 工具审批策略
     * @param approvalGrants 会话审批授权记录
     * @param properties Weaver 运行配置
     * @return 单例运行服务
     */
    @Bean
    public RunService runService(
            SessionService sessions,
            SessionEventStore eventStore,
            ModelClient model,
            ToolRegistry tools,
            ApprovalPolicy approvalPolicy,
            ApprovalGrantStore approvalGrants,
            WeaverProperties properties) {
        return new RunService(new RunServiceDependencies(
                sessions,
                eventStore,
                model,
                tools,
                approvalPolicy,
                approvalGrants,
                properties.maxSteps(),
                Duration.ofSeconds(properties.approval().timeoutSeconds()),
                RunServiceDependencies.defaultEmitterFactory()));
    }

    /**
     * 按配置协议创建模型客户端。
     *
     * @param properties Weaver 运行与模型配置
     * @return 当前配置对应的模型客户端
     * @throws IllegalArgumentException 协议不受支持时抛出
     */
    @Bean
    public ModelClient modelClient(WeaverProperties properties) {
        return switch (properties.model().protocol()) {
            case "openai-chat-completions" ->
                    new OpenAiChatCompletionsModelClient(properties.model());
            case "anthropic-messages" ->
                    new AnthropicMessagesModelClient(properties.model());
            default -> throw new IllegalArgumentException(
                    "unsupported model protocol: " + properties.model().protocol());
        };
    }
}
