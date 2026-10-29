package cn.yhjyhj.weaver.core.approval;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ApprovalRuleTest {

    @Test
    void allowsWildcardToolAndOperation() {
        ApprovalRule rule =
                assertDoesNotThrow(() -> new ApprovalRule(null, null, ApprovalDecision.ASK));

        assertNull(rule.toolName());
        assertNull(rule.operation());
    }

    @Test
    void acceptsExactToolAndOperation() {
        ApprovalRule rule = new ApprovalRule("git", "commit", ApprovalDecision.ASK);

        assertEquals("git", rule.toolName());
        assertEquals("commit", rule.operation());
    }

    @Test
    void rejectsNullDecision() {
        assertThrows(
                NullPointerException.class,
                () -> new ApprovalRule("file", "write", null));
    }
}
