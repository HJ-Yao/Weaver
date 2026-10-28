package cn.yhjyhj.weaver.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yhjyhj.weaver.core.events.ToolResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    @Test
    void rejectsDuplicateToolNames() {
        Tool first = tool("file");
        Tool duplicate = tool("file");

        assertThrows(
                IllegalArgumentException.class,
                () -> new ToolRegistry(List.of(first, duplicate)));
    }

    @Test
    void findsRegisteredToolByName() {
        Tool file = tool("file");
        ToolRegistry registry = new ToolRegistry(List.of(file));

        assertSame(file, registry.find("file").orElseThrow());
    }

    @Test
    void returnsEmptyWhenToolIsNotRegistered() {
        ToolRegistry registry = new ToolRegistry(List.of(tool("file")));

        assertTrue(registry.find("shell").isEmpty());
    }

    @Test
    void allowsParallelExecutionByDefault() {
        assertTrue(tool("file").parallelizable());
    }

    @Test
    void defaultsToIrreversibleOperations() {
        assertSame(Reversibility.IRREVERSIBLE, tool("file").reversibility());
    }

    @Test
    void parameterizedDeclarationsFallBackToNoArgumentDeclarations() {
        Tool tool = tool("file");

        assertSame(tool.reversibility(), tool.reversibility("{}"));
        assertSame(tool.parallelizable(), tool.parallelizable("{}"));
    }

    @Test
    void returnsModelSpecsSortedByToolName() {
        Tool zebra = tool("zebra");
        Tool alpha = tool("alpha");
        ToolRegistry registry = new ToolRegistry(List.of(zebra, alpha));

        List<ToolSpec> specs = registry.specs();

        assertEquals(List.of("alpha", "zebra"), specs.stream()
                .map(ToolSpec::name)
                .toList());
    }

    @Test
    void returnsImmutableModelSpecs() {
        List<ToolSpec> specs = new ToolRegistry(List.of(tool("file"))).specs();

        assertThrows(
                UnsupportedOperationException.class,
                () -> specs.add(new ToolSpec("shell", "shell", "{}")));
    }

    private static Tool tool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ToolResult execute(String argumentsJson) {
                return new ToolResult("ok", false, 0L);
            }
        };
    }
}
