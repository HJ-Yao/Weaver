package cn.yhjyhj.weaver.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 守护 weaver-core 的依赖边界，防止其被框架或上层模块污染。
 */
public class ArchitectureGuardTest {

    /** core 编译产物中禁止出现的框架命名空间。 */
    private static final List<String> FORBIDDEN_FRAMEWORK_NAMESPACES =
            List.of("org/springframework", "jakarta/", "org/springframework/boot");

    /** core 编译产物中禁止引用的上层模块命名空间。 */
    private static final List<String> FORBIDDEN_UPPER_LAYER_NAMESPACES =
            List.of(
                    "cn/yhjyhj/weaver/server",
                    "cn/yhjyhj/weaver/plugins",
                    "cn/yhjyhj/weaver/mcp");

    /** 编译后的 class 文件所在目录。 */
    private static final Path CLASSES_DIRECTORY = Path.of("target/classes");

    /** core 不得依赖 Spring 或 Jakarta 命名空间。 */
    @Test
    void coreClassesContainNoFrameworkNamespaces() throws IOException {
        assertClassFilesContainNo(FORBIDDEN_FRAMEWORK_NAMESPACES);
    }

    /** core 不得反向依赖 server、plugins 或 mcp。 */
    @Test
    void coreClassesDoNotReferenceUpperLayers() throws IOException {
        assertClassFilesContainNo(FORBIDDEN_UPPER_LAYER_NAMESPACES);
    }

    /**
     * 逐字节扫描全部编译产物，失败信息包含首个违规 class 与命名空间。
     *
     * @param forbiddenNamespaces 禁止出现在 class 文件中的命名空间
     * @throws IOException 无法读取编译产物时抛出
     */
    private static void assertClassFilesContainNo(List<String> forbiddenNamespaces)
            throws IOException {
        assertTrue(Files.isDirectory(CLASSES_DIRECTORY), "target/classes 必须存在");

        try (var paths = Files.walk(CLASSES_DIRECTORY)) {
            List<Path> classFiles =
                    paths.filter(path -> path.toString().endsWith(".class")).toList();
            assertFalse(classFiles.isEmpty(), "target/classes 必须包含 class 文件");

            for (Path classFile : classFiles) {
                String classBytes =
                        new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
                for (String namespace : forbiddenNamespaces) {
                    assertFalse(
                            classBytes.contains(namespace),
                            () -> classFile + " 不得引用 " + namespace);
                }
            }
        }
    }
}
