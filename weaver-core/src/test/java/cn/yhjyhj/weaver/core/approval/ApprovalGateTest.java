package cn.yhjyhj.weaver.core.approval;

import static org.junit.jupiter.api.Assertions.assertFalse;

import cn.yhjyhj.weaver.core.model.StreamChunk;
import cn.yhjyhj.weaver.core.tool.Reversibility;
import org.junit.jupiter.api.Test;

class ApprovalGateTest {

    @Test
    void denyAllIsSafeDefaultWithoutUi() {
        ApprovalGate gate = ApprovalGate.denyAll();

        assertFalse(gate.requestApproval(
                new StreamChunk.ToolCall("call-1", "shell", "{}"),
                Reversibility.IRREVERSIBLE));
    }
}
