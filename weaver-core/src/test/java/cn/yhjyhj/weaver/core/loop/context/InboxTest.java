package cn.yhjyhj.weaver.core.loop.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class InboxTest {

    @Test
    void pollsTurnEntriesInFifoOrderAndAssignsIds() {
        Inbox inbox = new Inbox();

        Inbox.Entry first = inbox.offer("first", Inbox.Target.TURN);
        Inbox.Entry second = inbox.offer("second", Inbox.Target.TURN);

        assertEquals("first", first.content());
        assertTrue(first.id().startsWith("in-"));
        assertEquals(first, inbox.pollTurn());
        assertEquals(second, inbox.pollTurn());
    }

    @Test
    void returnsNullWhenNoTurnEntryIsPending() {
        Inbox inbox = new Inbox();

        assertNull(inbox.pollTurn());
        assertFalse(inbox.hasPending());
    }

    @Test
    void ignoresBlankInputs() {
        Inbox inbox = new Inbox();

        assertNull(inbox.offer("", Inbox.Target.TURN));
        assertNull(inbox.offer(" \t\n", Inbox.Target.STEP));

        assertEquals(0, inbox.size());
        assertTrue(inbox.drainStep().isEmpty());
        assertNull(inbox.pollTurn());
    }

    @Test
    void rejectsNullContent() {
        Inbox inbox = new Inbox();

        assertThrows(
                NullPointerException.class,
                () -> inbox.offer(null, Inbox.Target.TURN));
    }

    @Test
    void drainStepReturnsOnlyStepEntriesInArrivalOrder() {
        Inbox inbox = new Inbox();
        Inbox.Entry firstTurn = inbox.offer("turn", Inbox.Target.TURN);
        Inbox.Entry firstStep = inbox.offer("step 1", Inbox.Target.STEP);
        Inbox.Entry secondStep = inbox.offer("step 2", Inbox.Target.STEP);

        assertEquals(List.of(firstStep, secondStep), inbox.drainStep());
        assertEquals(List.of(firstTurn), inbox.snapshot());
        assertTrue(inbox.drainStep().isEmpty());
    }

    @Test
    void promotesPendingStepEntriesToTurnWithoutChangingTheirOrder() {
        Inbox inbox = new Inbox();
        Inbox.Entry first = inbox.offer("first", Inbox.Target.STEP);
        Inbox.Entry second = inbox.offer("second", Inbox.Target.STEP);

        inbox.promoteStepToTurn();

        assertEquals(
                List.of(
                        new Inbox.Entry(first.id(), Inbox.Target.TURN, first.content()),
                        new Inbox.Entry(second.id(), Inbox.Target.TURN, second.content())),
                inbox.snapshot());
        assertEquals("first", inbox.pollTurn().content());
        assertEquals("second", inbox.pollTurn().content());
    }

    @Test
    void rejectsNewEntriesWhenCapacityIsReached() {
        Inbox inbox = new Inbox(2);

        inbox.offer("first", Inbox.Target.STEP);
        inbox.offer("second", Inbox.Target.TURN);

        assertNull(inbox.offer("third", Inbox.Target.STEP));
        assertEquals(2, inbox.size());
    }

    @Test
    void removesAndReplacesEntriesById() {
        Inbox inbox = new Inbox();
        Inbox.Entry removable = inbox.offer("remove me", Inbox.Target.STEP);
        Inbox.Entry replaceable = inbox.offer("old", Inbox.Target.TURN);

        assertTrue(inbox.remove(removable.id()));
        assertFalse(inbox.remove("missing"));
        assertEquals(
                new Inbox.Entry(replaceable.id(), Inbox.Target.TURN, "new"),
                inbox.replace(replaceable.id(), "new"));
        assertNull(inbox.replace("missing", "new"));
        assertNull(inbox.replace(replaceable.id(), " \t"));

        assertEquals(
                List.of(new Inbox.Entry(
                        replaceable.id(), Inbox.Target.TURN, "new")),
                inbox.snapshot());
    }

    @Test
    void snapshotIsReadOnlyAndClearDropsAllInputs() {
        Inbox inbox = new Inbox();
        inbox.offer("step", Inbox.Target.STEP);
        inbox.offer("turn", Inbox.Target.TURN);

        List<Inbox.Entry> snapshot = inbox.snapshot();
        assertEquals(2, snapshot.size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.add(new Inbox.Entry("manual", Inbox.Target.TURN, "x")));

        inbox.clear();

        assertEquals(0, inbox.size());
        assertFalse(inbox.hasPending());
    }

    @Test
    void doesNotLoseMessagesWhenOffersAndConsumersRunConcurrently() throws Exception {
        int producerCount = 4;
        int messagesPerProducer = 250;
        int messageCount = producerCount * messagesPerProducer;
        Inbox inbox = new Inbox(messageCount);
        ExecutorService executor = Executors.newFixedThreadPool(producerCount + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch producersDone = new CountDownLatch(producerCount);
        ConcurrentLinkedQueue<String> consumed = new ConcurrentLinkedQueue<>();
        AtomicInteger acceptedCount = new AtomicInteger();

        try {
            for (int producer = 0; producer < producerCount; producer++) {
                int producerId = producer;
                executor.submit(() -> {
                    try {
                        start.await();
                        for (int index = 0; index < messagesPerProducer; index++) {
                            Inbox.Target target = index % 2 == 0
                                    ? Inbox.Target.STEP
                                    : Inbox.Target.TURN;
                            Inbox.Entry entry =
                                    inbox.offer(producerId + ":" + index, target);
                            if (entry != null) {
                                acceptedCount.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    } finally {
                        producersDone.countDown();
                    }
                });
            }
            executor.submit(() -> {
                try {
                    start.await();
                    while (producersDone.getCount() > 0 || inbox.hasPending()) {
                        for (Inbox.Entry entry : inbox.drainStep()) {
                            consumed.add(entry.id());
                        }
                        Inbox.Entry entry = inbox.pollTurn();
                        if (entry != null) {
                            consumed.add(entry.id());
                        }
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            });

            start.countDown();
            assertTrue(producersDone.await(10, TimeUnit.SECONDS));
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        Set<String> uniqueIds = consumed.stream().collect(Collectors.toSet());
        assertEquals(messageCount, acceptedCount.get());
        assertEquals(messageCount, consumed.size());
        assertEquals(messageCount, uniqueIds.size());
    }
}
