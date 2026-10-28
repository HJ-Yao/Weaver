package cn.yhjyhj.weaver.core.loop.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RuleIntentClassifierTest {

    private final RuleIntentClassifier classifier = new RuleIntentClassifier();

    @Test
    void treatsBlankInputAsClarify() {
        assertEquals(Intent.CLARIFY, classifier.classify(""));
        assertEquals(Intent.CLARIFY, classifier.classify("   "));
        assertEquals(Intent.CLARIFY, classifier.classify(null));
    }

    @Test
    void detectsFileOperationAsToolUse() {
        assertEquals(Intent.TOOL_USE, classifier.classify("读取 src/Main.java"));
    }

    @Test
    void detectsCommandAsToolUse() {
        assertEquals(Intent.TOOL_USE, classifier.classify("run git status"));
    }

    @Test
    void detectsCommonExecutableAsToolUse() {
        assertEquals(Intent.TOOL_USE, classifier.classify("mvn test"));
    }

    @Test
    void defaultsOrdinaryConversationToChat() {
        assertEquals(Intent.CHAT, classifier.classify("你好"));
    }
}
