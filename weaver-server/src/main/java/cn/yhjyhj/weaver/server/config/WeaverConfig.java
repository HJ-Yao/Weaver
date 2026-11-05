package cn.yhjyhj.weaver.server.config;

import cn.yhjyhj.weaver.core.approval.ApprovalGrantStore;
import cn.yhjyhj.weaver.core.approval.ApprovalPolicy;
import cn.yhjyhj.weaver.core.approval.InMemoryApprovalGrantStore;
import cn.yhjyhj.weaver.core.approval.RuleBasedApprovalPolicy;
import cn.yhjyhj.weaver.core.events.SessionEventStore;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import cn.yhjyhj.weaver.core.tool.builtin.FileTool;
import cn.yhjyhj.weaver.core.tool.builtin.GitTool;
import cn.yhjyhj.weaver.core.tool.builtin.ShellTool;
import cn.yhjyhj.weaver.server.service.RunService;
import cn.yhjyhj.weaver.server.service.RunServiceDependencies;
import cn.yhjyhj.weaver.server.service.SessionService;
import cn.yhjyhj.weaver.server.service.SessionTitleGenerator;
import cn.yhjyhj.weaver.server.settings.DefaultModelClientProvider;
import cn.yhjyhj.weaver.server.settings.ModelClientProvider;
import cn.yhjyhj.weaver.server.settings.ModelSettingsService;
import cn.yhjyhj.weaver.server.settings.ModelSettingsStore;
import cn.yhjyhj.weaver.server.store.SessionStore;
import cn.yhjyhj.weaver.server.store.SqliteEventStore;
import cn.yhjyhj.weaver.server.store.SqliteSessionStore;
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
     * 创建与事件事实源共用数据库文件的 SQLite 会话元数据存储。
     *
     * @param properties Weaver 运行配置
     * @return 可持久化会话标题与更新时间的存储
     */
    @Bean(destroyMethod = "close")
    public SessionStore sessionStore(WeaverProperties properties) {
        return new SqliteSessionStore(properties.storePath());
    }

    /**
     * 创建会话服务，并在启动装配阶段补齐历史事件对应的元数据。
     *
     * @param store 会话元数据存储
     * @param events 会话事件事实源
     * @return 已完成 backfill 的单例会话服务
     */
    @Bean
    public SessionService sessionService(SessionStore store, SessionEventStore events) {
        SessionService service = new SessionService(store, events);
        service.backfill();
        return service;
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
     * 创建固定位于用户主目录下的模型设置文件存储。
     *
     * @return 使用 {@code ~/.weaver/config.json} 的存储
     */
    @Bean
    public ModelSettingsStore modelSettingsStore() {
        Path configPath =
                Path.of(System.getProperty("user.home"), ".weaver", "config.json");
        return new ModelSettingsStore(configPath);
    }

    /**
     * 创建按持久化激活配置解析并缓存客户端的 provider。
     *
     * @param store 模型设置存储
     * @param properties 环境模型与生成参数
     * @return 感知配置变化的模型客户端 provider
     */
    @Bean
    public ModelClientProvider modelClientProvider(
            ModelSettingsStore store, WeaverProperties properties) {
        return new DefaultModelClientProvider(
                () -> ModelSettingsService.resolveActive(
                        store.load(), properties.model()),
                properties.model());
    }

    /**
     * 创建模型设置服务。
     *
     * @param store 模型设置存储
     * @param properties 环境模型兜底配置
     * @param modelProvider 模型客户端缓存
     * @return 模型设置业务服务
     */
    @Bean
    public ModelSettingsService modelSettingsService(
            ModelSettingsStore store,
            WeaverProperties properties,
            ModelClientProvider modelProvider) {
        return new ModelSettingsService(store, properties, modelProvider);
    }

    /**
     * 创建首轮后自动命名会话的标题生成器。
     *
     * @return 硬超时为 5 秒的标题生成器
     */
    @Bean
    public SessionTitleGenerator sessionTitleGenerator() {
        return new SessionTitleGenerator(Duration.ofSeconds(5));
    }

    /**
     * 使用进程级依赖与应用配置创建运行服务。
     *
     * @param sessions 会话元数据服务
     * @param eventStore 会话事件事实源
     * @param modelProvider 每轮解析当前模型的 provider
     * @param titleGenerator 首轮后生成会话标题的辅助生成器
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
            ModelClientProvider modelProvider,
            SessionTitleGenerator titleGenerator,
            ToolRegistry tools,
            ApprovalPolicy approvalPolicy,
            ApprovalGrantStore approvalGrants,
            WeaverProperties properties) {
        return new RunService(new RunServiceDependencies(
                sessions,
                eventStore,
                modelProvider,
                titleGenerator,
                tools,
                approvalPolicy,
                approvalGrants,
                properties.maxSteps(),
                Duration.ofSeconds(properties.approval().timeoutSeconds()),
                RunServiceDependencies.defaultEmitterFactory()));
    }

}
