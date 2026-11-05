package cn.yhjyhj.weaver.server.service;

import cn.yhjyhj.weaver.core.tool.ToolRegistry;
import java.nio.file.Path;

/** 按工作区根目录构建内置工具注册表。 */
@FunctionalInterface
public interface ToolRegistryFactory {

    /**
     * 为一次会话运行创建受工作区边界约束的工具集合。
     *
     * @param root 本次运行使用的工作区根目录
     * @return 绑定到该根目录的工具注册表
     */
    ToolRegistry create(Path root);
}
