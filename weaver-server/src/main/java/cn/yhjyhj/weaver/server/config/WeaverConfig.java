package cn.yhjyhj.weaver.server.config;

import cn.yhjyhj.weaver.core.approval.ApprovalGate;
import cn.yhjyhj.weaver.core.approval.ApprovalPolicy;
import cn.yhjyhj.weaver.core.approval.RuleBasedApprovalPolicy;
import cn.yhjyhj.weaver.core.events.InMemorySessionEventStore;
import cn.yhjyhj.weaver.core.events.SessionEventStore;
import cn.yhjyhj.weaver.core.model.ModelClient;
import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import cn.yhjyhj.weaver.core.tool.builtin.FileTool;
import cn.yhjyhj.weaver.core.tool.builtin.GitTool;
import cn.yhjyhj.weaver.core.tool.builtin.ShellTool;
import cn.yhjyhj.weaver.server.model.EchoModelClient;
import java.nio.file.Path;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配 Weaver 的进程级基础设施；单次运行对象由运行服务创建。 */
@Configuration
public class WeaverConfig {

    /**
     * 创建进程内会话事件事实源。
     *
     * @return 空的并发事件存储
     */
    @Bean
    public SessionEventStore eventStore() {
        return new InMemorySessionEventStore();
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
     * 创建默认规则审批策略。
     *
     * @return 按工具回滚性修正的内置策略
     */
    @Bean
    public ApprovalPolicy approvalPolicy() {
        return RuleBasedApprovalPolicy.defaults();
    }

    /**
     * 创建无 UI 场景的安全审批闸门。
     *
     * @return 一律拒绝 ASK 请求的审批闸门
     */
    @Bean
    public ApprovalGate approvalGate() {
        return ApprovalGate.denyAll();
    }

    /**
     * 创建当前阶段的回显模型客户端。
     *
     * @return 不访问外部模型的回显实现
     */
    @Bean
    public ModelClient modelClient() {
        return new EchoModelClient();
    }
}
