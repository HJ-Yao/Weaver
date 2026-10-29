package cn.yhjyhj.weaver.core.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ApprovalRulesTest {

    @Test
    void returnsTextOperationFromJson() {
        assertEquals(
                "write",
                ApprovalRules.operationOf("""
                        {"op":"write","path":"src/Main.java"}
                        """));
    }

    @Test
    void returnsNullForMissingNonTextMalformedOrNullArguments() {
        assertNull(ApprovalRules.operationOf("{}"));
        assertNull(ApprovalRules.operationOf("""
                {"op":1}
                """));
        assertNull(ApprovalRules.operationOf("{"));
        assertNull(ApprovalRules.operationOf(null));
        assertNull(ApprovalRules.operationOf("[]"));
    }
}
