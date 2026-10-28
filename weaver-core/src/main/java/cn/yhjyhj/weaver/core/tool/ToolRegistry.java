package cn.yhjyhj.weaver.core.tool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 按模型给出的工具名查找工具实例的不可变注册表。 */
public final class ToolRegistry {

    private final Map<String, Tool> tools; // 工具名到工具实例的只读快照

    /**
     * 用一组工具创建注册表。
     *
     * @param tools 待注册工具
     * @throws IllegalArgumentException 工具名重复时抛出
     */
    public ToolRegistry(List<Tool> tools) {
        Map<String, Tool> registered = new HashMap<>();
        for (Tool tool : List.copyOf(tools)) {
            String name = Objects.requireNonNull(tool.name(), "tool name");
            if (registered.putIfAbsent(name, tool) != null) {
                throw new IllegalArgumentException("duplicate tool name: " + name);
            }
        }
        this.tools = Map.copyOf(registered);
    }

    /**
     * 按名称查找工具。
     *
     * @param name 模型给出的工具名
     * @return 匹配的工具；未注册时为空
     */
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 返回按工具名排序的只读描述列表，供模型请求使用。
     *
     * @return 顺序稳定且不可修改的工具描述列表
     */
    public List<ToolSpec> specs() {
        return tools.values().stream()
                .map(tool -> new ToolSpec(
                        tool.name(), tool.description(), tool.parametersSchema()))
                .sorted((left, right) -> left.name().compareTo(right.name()))
                .toList();
    }

    /**
     * 创建空注册表。
     *
     * @return 不包含任何工具的注册表
     */
    public static ToolRegistry empty() {
        return new ToolRegistry(List.of());
    }
}
