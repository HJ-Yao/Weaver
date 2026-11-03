package cn.yhjyhj.weaver.core.loop.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class InboxTest {

    @Test
    void pollsInputsInFifoOrder() {
        Inbox inbox = new Inbox();

        inbox.offer("first");
        inbox.offer("second");

        assertEquals("first", inbox.poll());
        assertEquals("second", inbox.poll());
    }

    @Test
    void returnsNullWhenEmpty() {
        Inbox inbox = new Inbox();

        assertNull(inbox.poll());
        assertTrue(inbox.isEmpty());
    }

    @Test
    void ignoresBlankInputs() {
        Inbox inbox = new Inbox();

        inbox.offer("");
        inbox.offer(" \t\n");

        assertTrue(inbox.isEmpty());
        assertNull(inbox.poll());
    }

    @Test
    void doesNotLoseMessagesWhenOffersAndPollsRunConcurrently() throws Exception {
        Inbox inbox = new Inbox();
        int producerCount = 4;
        int messagesPerProducer = 250;
        int messageCount = producerCount * messagesPerProducer;
        ExecutorService executor = Executors.newFixedThreadPool(producerCount + 1);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch producersDone = new CountDownLatch(producerCount);
        ConcurrentLinkedQueue<String> consumed = new ConcurrentLinkedQueue<>();

        try {
            for (int producer = 0; producer < producerCount; producer++) {
                int producerId = producer;
                executor.submit(() -> {
                    try {
                        start.await();
                        for (int index = 0; index < messagesPerProducer; index++) {
                            inbox.offer(producerId + ":" + index);
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
                    while (producersDone.getCount() > 0 || !inbox.isEmpty()) {
                        String input = inbox.poll();
                        if (input != null) {
                            consumed.add(input);
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

        Set<String> uniqueInputs = consumed.stream().collect(Collectors.toSet());
        assertEquals(messageCount, consumed.size());
        assertEquals(messageCount, uniqueInputs.size());
    }
}
