package cn.yhjyhj.weaver.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.yhjyhj.weaver.core.tool.ToolSpec;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelRequestTest {

    @Test
    void twoArgumentConstructorUsesEmptyToolList() {
        ModelRequest request = new ModelRequest("input", List.of());

        assertEquals(List.of(), request.tools());
    }

    @Test
    void defensivelyCopiesToolsAndHistory() {
        ArrayList<ToolSpec> tools = new ArrayList<>(List.of(
                new ToolSpec("file", "file", "{}")));
        ArrayList<ModelMessage> history = new ArrayList<>(List.of(
                new ModelMessage.User("hello")));

        ModelRequest request = new ModelRequest("input", history, tools);
        tools.clear();
        history.clear();

        assertEquals(List.of(new ToolSpec("file", "file", "{}")), request.tools());
        assertEquals(1, request.history().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.tools().add(new ToolSpec("shell", "shell", "{}")));
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.history().add(new ModelMessage.User("later")));
    }

    @Test
    void assistantDefensivelyCopiesToolCalls() {
        ArrayList<StreamChunk.ToolCall> toolCalls = new ArrayList<>(List.of(
                new StreamChunk.ToolCall("call-1", "file", "{}")));

        ModelMessage.Assistant assistant = new ModelMessage.Assistant("", toolCalls);
        toolCalls.clear();

        assertEquals(1, assistant.toolCalls().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> assistant.toolCalls().add(
                        new StreamChunk.ToolCall("call-2", "shell", "{}")));
    }
}
