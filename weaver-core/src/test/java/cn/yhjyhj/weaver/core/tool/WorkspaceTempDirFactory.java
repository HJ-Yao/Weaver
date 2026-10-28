package cn.yhjyhj.weaver.core.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

/** 将测试工作区建在可写的模块 target 目录，避免系统临时目录 ACL 干扰。 */
public final class WorkspaceTempDirFactory implements TempDirFactory {

    /**
     * 创建唯一的临时工作区。
     *
     * @param annotatedElementContext 使用 TempDir 的字段或参数上下文
     * @param extensionContext 当前测试扩展上下文
     * @return 位于构建输出目录内的临时工作区
     * @throws Exception 目录创建失败时抛出
     */
    @Override
    public Path createTempDirectory(
            AnnotatedElementContext annotatedElementContext,
            ExtensionContext extensionContext) throws Exception {
        Path base = Path.of("target", "test-tmp");
        Files.createDirectories(base);
        return Files.createTempDirectory(base.toAbsolutePath().normalize(), "weaver-");
    }
}
