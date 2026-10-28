package cn.yhjyhj.weaver.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ToolSpecTest {

    @Test
    void exposesNameDescriptionAndParametersSchema() {
        ToolSpec spec = new ToolSpec(
                "file",
                "Read, write or list files.",
                "{\"type\":\"object\"}");

        assertEquals("file", spec.name());
        assertEquals("Read, write or list files.", spec.description());
        assertEquals("{\"type\":\"object\"}", spec.parametersSchema());
    }
}
