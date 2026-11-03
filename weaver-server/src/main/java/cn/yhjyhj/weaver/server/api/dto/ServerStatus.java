package cn.yhjyhj.weaver.server.api.dto;

/**
 * 服务健康状态与运行环境快照。
 *
 * @param status 服务状态；能返回响应时固定为 {@code ok}
 * @param storage 当前会话事件存储类型
 * @param wal SQLite 是否启用 WAL
 * @param workspace 当前工作区的绝对路径
 * @param maxSteps 单轮 Agent 允许执行的最大步数
 * @param uptimeSeconds JVM 进程已运行的秒数
 * @param model 当前配置的模型名
 * @param protocol 当前模型协议
 */
public record ServerStatus(
        String status,
        String storage,
        boolean wal,
        String workspace,
        int maxSteps,
        long uptimeSeconds,
        String model,
        String protocol) {}
