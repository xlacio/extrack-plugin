package ru.extrack.plugin.console;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A broken plugin can print thousands of errors a second, so the queue is small and the rest is dropped:
 * the panel groups repeats of one error anyway.
 */
public final class ConsoleQueue {

    private static final int CAPACITY = 1_000;

    private final ConcurrentLinkedQueue<ConsoleRecord> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger                        size  = new AtomicInteger();

    /** Cheap check before building a record, stack traces are not free to render. */
    boolean isFull() {
        return this.size.get() >= CAPACITY;
    }

    void offer(ConsoleRecord record) {
        if (this.size.incrementAndGet() > CAPACITY) {
            this.size.decrementAndGet();
            return;
        }
        this.queue.offer(record);
    }

    public List<ConsoleRecord> poll(int max) {
        List<ConsoleRecord> batch = null;
        ConsoleRecord record;
        while ((batch == null || batch.size() < max) && (record = this.queue.poll()) != null) {
            if (batch == null) batch = new ArrayList<>();
            batch.add(record);
            this.size.decrementAndGet();
        }
        return batch == null ? Collections.<ConsoleRecord>emptyList() : batch;
    }
}
