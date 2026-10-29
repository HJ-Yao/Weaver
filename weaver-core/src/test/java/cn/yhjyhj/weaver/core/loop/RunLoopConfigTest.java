package cn.yhjyhj.weaver.core.loop;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RunLoopConfigTest {

    @Test
    void defaultsUseUnattendedConfiguration() {
        RunLoopConfig config = RunLoopConfig.defaults();

        assertEquals(RunLoop.DEFAULT_MAX_STEPS, config.maxSteps());
        assertNotNull(config.streamSink());
        assertTrue(config.toolRegistry().specs().isEmpty());
        assertNotNull(config.approvalPolicy());
        assertFalse(config.approvalGate().isInteractive());
        assertNotNull(config.approvalGrants());
    }

    @Test
    void acceptsPositiveMaxSteps() {
        RunLoopConfig defaults = RunLoopConfig.defaults();

        RunLoopConfig config = new RunLoopConfig(
                1,
                defaults.streamSink(),
                defaults.toolRegistry(),
                defaults.approvalPolicy(),
                defaults.approvalGate(),
                defaults.approvalGrants());

        assertEquals(1, config.maxSteps());
    }

    @Test
    void rejectsNonPositiveMaxSteps() {
        RunLoopConfig defaults = RunLoopConfig.defaults();

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new RunLoopConfig(
                                0,
                                defaults.streamSink(),
                                defaults.toolRegistry(),
                                defaults.approvalPolicy(),
                                defaults.approvalGate(),
                                defaults.approvalGrants())),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new RunLoopConfig(
                                -1,
                                defaults.streamSink(),
                                defaults.toolRegistry(),
                                defaults.approvalPolicy(),
                                defaults.approvalGate(),
                                defaults.approvalGrants())));
    }

    @Test
    void rejectsNullCollaborators() {
        RunLoopConfig defaults = RunLoopConfig.defaults();

        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new RunLoopConfig(
                                50,
                                null,
                                defaults.toolRegistry(),
                                defaults.approvalPolicy(),
                                defaults.approvalGate(),
                                defaults.approvalGrants())),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new RunLoopConfig(
                                50,
                                defaults.streamSink(),
                                null,
                                defaults.approvalPolicy(),
                                defaults.approvalGate(),
                                defaults.approvalGrants())),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new RunLoopConfig(
                                50,
                                defaults.streamSink(),
                                defaults.toolRegistry(),
                                null,
                                defaults.approvalGate(),
                                defaults.approvalGrants())),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new RunLoopConfig(
                                50,
                                defaults.streamSink(),
                                defaults.toolRegistry(),
                                defaults.approvalPolicy(),
                                null,
                                defaults.approvalGrants())),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new RunLoopConfig(
                                50,
                                defaults.streamSink(),
                                defaults.toolRegistry(),
                                defaults.approvalPolicy(),
                                defaults.approvalGate(),
                                null)));
    }
}
