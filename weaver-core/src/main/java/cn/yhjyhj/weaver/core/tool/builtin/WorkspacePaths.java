package cn.yhjyhj.weaver.core.tool.builtin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

/** 将工具请求路径限制在真实工作区根目录内。 */
final class WorkspacePaths {

    private static final Pattern WINDOWS_DRIVE_PATH =
            Pattern.compile("^[A-Za-z]:"); // 跨平台识别 Windows 盘符绝对路径

    private WorkspacePaths() {}

    /**
     * 解析并校验工作区内路径。
     *
     * @param realRoot 构造工具时取得的工作区真实根目录
     * @param path 模型给出的相对路径
     * @return 规范化且仍在真实根目录内的目标路径
     * @throws IOException 查询真实路径失败时抛出
     * @throws EscapingPathException 路径绝对、越界或经过符号链接时抛出
     */
    static Path resolve(Path realRoot, String path) throws IOException {
        Objects.requireNonNull(realRoot, "realRoot");
        final Path requested;
        try {
            requested = Path.of(Objects.requireNonNull(path, "path"));
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException(
                    "invalid path: " + exception.getMessage(), exception);
        }
        if (isAbsoluteRequest(path, requested)) {
            throw new EscapingPathException();
        }

        Path target = realRoot.resolve(requested).normalize();
        if (!target.startsWith(realRoot)) {
            throw new EscapingPathException();
        }

        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(target)) {
                throw new EscapingPathException();
            }
            verifyRealPath(realRoot, target);
            return target;
        }

        Path existingAncestor = target;
        while (!Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
            existingAncestor = existingAncestor.getParent();
            if (existingAncestor == null || !existingAncestor.startsWith(realRoot)) {
                throw new EscapingPathException();
            }
        }
        if (Files.isSymbolicLink(existingAncestor)) {
            throw new EscapingPathException();
        }
        verifyRealPath(realRoot, existingAncestor);

        Path cursor = existingAncestor;
        for (Path segment : existingAncestor.relativize(target)) {
            cursor = cursor.resolve(segment);
            // 写目标尚不存在，但中间目录仍可能被替换为符号链接。
            if (Files.isSymbolicLink(cursor)) {
                throw new EscapingPathException();
            }
        }
        return target;
    }

    private static boolean isAbsoluteRequest(String original, Path requested) {
        return requested.isAbsolute()
                || original.startsWith("/")
                || original.startsWith("\\")
                || WINDOWS_DRIVE_PATH.matcher(original).find();
    }

    private static void verifyRealPath(Path realRoot, Path candidate) throws IOException {
        Path realCandidate = candidate.toRealPath();
        if (!realCandidate.startsWith(realRoot)) {
            throw new EscapingPathException();
        }
    }

    /** 内部信号：请求路径落在真实工作区之外。 */
    static final class EscapingPathException extends RuntimeException {}
}
