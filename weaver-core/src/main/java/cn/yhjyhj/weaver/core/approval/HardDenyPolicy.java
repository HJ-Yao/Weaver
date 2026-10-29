package cn.yhjyhj.weaver.core.approval;

import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.builtin.WorkspacePaths;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** 对受保护目录的写与删一律拒绝；读不在此处拦截。 */
public final class HardDenyPolicy {

    /** 匹配路径中的受保护目录名，避免把 .gitignore 当成 .git。 */
    private static final Pattern PROTECTED_DIRECTORY =
            Pattern.compile("(?i)(?<![\\w.-])\\.(?:git|weaver)(?![\\w.-])");
    /** 匹配命令中的删除或移动意图，允许 Python 的 shutil.rmtree 形式。 */
    private static final Pattern DESTRUCTIVE_INTENT =
            Pattern.compile("(?i)(?<![\\w-])(?:rm|del|rmdir|rmtree|mv|move)(?![\\w-])");

    /**
     * 判断一次工具调用是否命中受保护目录的写删硬禁区。
     *
     * @param call 待审批的工具调用
     * @return 命中硬禁区时为 true
     * @throws NullPointerException call 为 null 时抛出
     */
    public boolean isDenied(StreamChunk.ToolCall call) {
        Objects.requireNonNull(call, "call");
        if ("file".equals(call.toolName())) {
            return deniesFileWrite(call.argumentsJson());
        }
        if ("shell".equals(call.toolName())) {
            return deniesShellMutation(call.argumentsJson());
        }
        return false;
    }

    private static boolean deniesFileWrite(String argumentsJson) {
        if (!"write".equals(ApprovalRules.operationOf(argumentsJson))) {
            return false;
        }
        String path = ApprovalRules.textArgument(argumentsJson, "path");
        if (path == null || path.isBlank()) {
            return false;
        }
        return WorkspacePaths.parseRelative(path)
                .map(HardDenyPolicy::containsProtectedSegment)
                .orElse(false);
    }

    private static boolean containsProtectedSegment(Path path) {
        for (Path segment : path) {
            String name = segment.toString();
            if (".git".equalsIgnoreCase(name) || ".weaver".equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean deniesShellMutation(String argumentsJson) {
        String command = ApprovalRules.textArgument(argumentsJson, "cmd");
        if (command == null || command.isBlank()) {
            return false;
        }
        // shell 检查只是启发式最后防线；任意系统目录的隔离留待后续里程碑。
        // TODO(M2): 将 /etc、/usr、C:\Windows 等系统目录纳入启用策略。
        String normalized = command.toLowerCase(Locale.ROOT);
        return PROTECTED_DIRECTORY.matcher(normalized).find()
                && DESTRUCTIVE_INTENT.matcher(normalized).find();
    }
}
