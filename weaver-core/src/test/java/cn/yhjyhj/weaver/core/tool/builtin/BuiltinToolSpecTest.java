package cn.yhjyhj.weaver.core.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.tool.Tool;
import cn.yhjyhj.weaver.core.tool.WorkspaceTempDirFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuiltinToolSpecTest {

    private static final ObjectMapper JSON = new ObjectMapper(); // 解析工具 schema 的测试映射器

    @TempDir(factory = WorkspaceTempDirFactory.class)
    Path workspace; // 每个测试独立的工作区

    @Test
    void builtinToolsExposeDescriptionsAndValidJsonSchemas() throws Exception {
        for (Tool tool : List.of(
                new FileTool(workspace),
                new ShellTool(workspace),
                new GitTool(workspace))) {
            assertFalse(tool.description().isBlank(), tool.name());
            assertTrue(JSON.readTree(tool.parametersSchema()).isObject(), tool.name());
        }
    }

    @Test
    void fileSchemaDescribesSupportedOperationsAndRequiredFields() throws Exception {
        JsonNode schema = JSON.readTree(new FileTool(workspace).parametersSchema());

        assertEquals(
                List.of("read", "write", "list"),
                JSON.convertValue(
                        schema.path("properties").path("op").path("enum"),
                        JSON.getTypeFactory().constructCollectionType(List.class, String.class)));
        assertEquals(
                List.of("op", "path"),
                JSON.convertValue(
                        schema.path("required"),
                        JSON.getTypeFactory().constructCollectionType(List.class, String.class)));
    }

    @Test
    void shellSchemaRequiresCommand() throws Exception {
        JsonNode schema = JSON.readTree(new ShellTool(workspace).parametersSchema());

        assertTrue(schema.path("properties").path("cmd").isObject());
        assertEquals("cmd", schema.path("required").get(0).asText());
    }

    @Test
    void gitSchemaListsSupportedOperations() throws Exception {
        JsonNode schema = JSON.readTree(new GitTool(workspace).parametersSchema());

        assertEquals(
                List.of("status", "diff", "log", "commit", "push"),
                JSON.convertValue(
                        schema.path("properties").path("op").path("enum"),
                        JSON.getTypeFactory().constructCollectionType(List.class, String.class)));
    }
}
