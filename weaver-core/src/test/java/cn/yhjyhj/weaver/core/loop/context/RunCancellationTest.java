package cn.yhjyhj.weaver.core.loop.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RunCancellationTest {

    @Test
    void reportsCancelledAfterCancel() {
        RunCancellation cancellation = new RunCancellation();

        assertFalse(cancellation.isCancelled());

        cancellation.cancel();

        assertTrue(cancellation.isCancelled());
    }

    @Test
    void runsListenerImmediatelyWhenRegisteredAfterCancel() {
        RunCancellation cancellation = new RunCancellation();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        cancellation.cancel();

        cancellation.onCancel(() -> {
            calls.incrementAndGet();
            callbackThread.set(Thread.currentThread());
        });

        assertEquals(1, calls.get());
        assertSame(Thread.currentThread(), callbackThread.get());
    }

    @Test
    void runsRegisteredListenerOnlyOnceAcrossRepeatedCancels() {
        RunCancellation cancellation = new RunCancellation();
        AtomicInteger calls = new AtomicInteger();
        cancellation.onCancel(calls::incrementAndGet);

        cancellation.cancel();
        cancellation.cancel();

        assertEquals(1, calls.get());
    }

    @Test
    void continuesNotifyingWhenOneListenerThrows() {
        RunCancellation cancellation = new RunCancellation();
        AtomicInteger callsAfterFailure = new AtomicInteger();
        cancellation.onCancel(() -> {
            throw new IllegalStateException("listener failed");
        });
        cancellation.onCancel(callsAfterFailure::incrementAndGet);

        cancellation.cancel();

        assertEquals(1, callsAfterFailure.get());
    }
}
