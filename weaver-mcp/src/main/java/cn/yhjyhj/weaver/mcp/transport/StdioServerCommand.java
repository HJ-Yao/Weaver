package cn.yhjyhj.weaver.mcp.transport;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 描述一个待启动的本地 MCP stdio 服务器进程。 */
public record StdioServerCommand(String command, List<String> args, Map<String, String> env, Path cwd) {

    /** 校验并冻结进程启动参数。 */
    public StdioServerCommand {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("command must not be blank");
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
    }
}
