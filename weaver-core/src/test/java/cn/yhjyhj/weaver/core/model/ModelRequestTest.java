package cn.yhjyhj.weaver.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.yhjyhj.weaver.core.events.SessionEvent;
import cn.yhjyhj.weaver.core.events.SessionSnapshot;
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
        ArrayList<SessionSnapshot.Message> history = new ArrayList<>(List.of(
                new SessionSnapshot.Message(SessionEvent.Role.USER, "hello")));

        ModelRequest request = new ModelRequest("input", history, tools);
        tools.clear();
        history.clear();

        assertEquals(List.of(new ToolSpec("file", "file", "{}")), request.tools());
        assertEquals(1, request.history().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.tools().add(new ToolSpec("shell", "shell", "{}")));
    }
}
