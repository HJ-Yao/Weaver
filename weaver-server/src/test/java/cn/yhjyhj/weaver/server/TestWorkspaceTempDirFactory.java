package cn.yhjyhj.weaver.server;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

/** 将服务端测试临时目录放在模块 target 下，避开系统临时目录 ACL。 */
public final class TestWorkspaceTempDirFactory implements TempDirFactory {

    /**
     * 创建唯一测试目录。
     *
     * @param annotatedElementContext 使用 TempDir 的字段或参数上下文
     * @param extensionContext 当前测试扩展上下文
     * @return 位于构建输出目录内的隔离目录
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
