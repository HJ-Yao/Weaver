package cn.yhjyhj.weaver.core.phase;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Phase 状态机的行为契约：先定义"应该怎样"，再实现。 */
class PhaseTest {

    @Test
    void initialStateIsIdleWithZeroCounters() {
        Phase p = Phase.idle();
        assertInstanceOf(Phase.Idle.class, p);
        assertEquals(0, p.turn());
        assertEquals(0, p.step());
        assertFalse(p.wakeRequested());
        assertFalse(p.aborted());
    }

    @Test
    void startingFromIdleEntersRunningWithStepZero() {
        Phase.Running r = Phase.idle().start();
        assertEquals(0, r.turn());
        assertEquals(0, r.step());
        assertFalse(r.aborted());
    }

    @Test
    void nextStepIncrementsStepWithinTurn() {
        Phase.Running r = Phase.idle().start().nextStep().nextStep();
        assertEquals(0, r.turn());
        assertEquals(2, r.step());
    }

    @Test
    void nextTurnIncrementsTurnAndResetsStep() {
        Phase.Running r = Phase.idle().start().nextStep().nextTurn();
        assertEquals(1, r.turn());
        assertEquals(0, r.step());
    }

    @Test
    void completeReturnsToIdleKeepingTurn() {
        Phase p = Phase.idle().start().nextTurn().complete();
        assertInstanceOf(Phase.Idle.class, p);
        assertEquals(1, p.turn());
        assertEquals(0, p.step());
    }

    @Test
    void abortEntersMaintenanceMarkingAborted() {
        Phase.Maintenance m = Phase.idle().start().nextStep().abort();
        assertTrue(m.aborted());
        assertEquals(0, m.turn());
        assertEquals(1, m.step());
    }

    @Test
    void wakeFlagSettableOnIdleAndRunning() {
        assertTrue(Phase.idle().wake().wakeRequested());
        assertTrue(Phase.idle().start().wake().wakeRequested());
    }

    @Test
    void allStatesExposeCountersThroughPhase() {
        assertTurnStep(Phase.idle());
        assertTurnStep(Phase.idle().start());
        assertTurnStep(Phase.idle().start().abort());
    }

    private static void assertTurnStep(Phase p) {
        assertTrue(p.turn() >= 0);
        assertTrue(p.step() >= 0);
    }
}