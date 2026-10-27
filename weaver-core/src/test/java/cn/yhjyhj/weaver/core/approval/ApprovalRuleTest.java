package cn.yhjyhj.weaver.core.approval;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ApprovalRuleTest {

    @Test
    void compilesPatternsWhenConstructed() {
        assertDoesNotThrow(() -> new ApprovalRule(
                "git", "^(status|diff|log)\\b.*", ApprovalDecision.ALLOW));
    }

    @Test
    void rejectsInvalidToolPatternAtConstruction() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApprovalRule("[", ".*", ApprovalDecision.ALLOW));
    }

    @Test
    void rejectsInvalidArgumentsPatternAtConstruction() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ApprovalRule("git", "[", ApprovalDecision.ALLOW));
    }
}
