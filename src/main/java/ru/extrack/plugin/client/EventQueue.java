package ru.extrack.plugin.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonWriter;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import ru.extrack.plugin.event.LogEvent;

/**
 * Lock-free event buffer. Lives as long as the plugin, so reconnecting after /extrack reload keeps
 * whatever was queued. When the panel is down for long the newest events are dropped, never the server.
 */
public final class EventQueue {

    private final ConcurrentLinkedDeque<LogEvent> deque   = new ConcurrentLinkedDeque<>();
    private final AtomicInteger                   size    = new AtomicInteger();
    private final AtomicLong                      dropped = new AtomicLong();
    private final AtomicLong                      droppedTotal = new AtomicLong();

    private volatile int capacity = 50_000;

    public void setCapacity(int capacity) {
        this.capacity = Math.max(1_000, capacity);
    }

    public boolean offer(LogEvent event) {
        if (this.size.incrementAndGet() > this.capacity) {
            this.size.decrementAndGet();
            this.dropped.incrementAndGet();
            this.droppedTotal.incrementAndGet();
            return false;
        }
        this.deque.offerLast(event);
        return true;
    }

    public int size() {
        return this.size.get();
    }

    /** Dropped since the previous call, for the periodic console warning. */
    public long takeDropped() {
        return this.dropped.getAndSet(0L);
    }

    public long droppedTotal() {
        return this.droppedTotal.get();
    }

    List<LogEvent> poll(int max) {
        List<LogEvent> batch = null;
        LogEvent event;
        while ((batch == null || batch.size() < max) && (event = this.deque.pollFirst()) != null) {
            if (batch == null) batch = new ArrayList<>(Math.min(max, Math.max(16, this.size.get())));
            batch.add(event);
            this.size.decrementAndGet();
        }
        return batch == null ? Collections.<LogEvent>emptyList() : batch;
    }

    /** Puts a failed batch back in front, in the original order. */
    void requeue(List<LogEvent> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            this.deque.offerFirst(batch.get(i));
            this.size.incrementAndGet();
        }
    }

    /** Writes what is left to disk on shutdown, one JSON object per line. */
    public void spool(File file, Logger logger) {
        if (this.deque.isEmpty()) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;

        int written = 0;
        try (Writer out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8))) {
            LogEvent event;
            while ((event = this.deque.pollFirst()) != null) {
                this.size.decrementAndGet();
                StringWriter line = new StringWriter(256);
                event.write(new JsonWriter(line));
                out.write(line.toString());
                out.write('\n');
                written++;
            }
        }
        catch (IOException exception) {
            logger.warning("Не удалось сохранить неотправленные события: " + exception.getMessage());
            return;
        }
        logger.info("Панель недоступна, " + written + " событий сохранены в " + file.getName() + " и уйдут после запуска.");
    }

    /** Loads events left by the previous run. Called off the main thread. */
    public void restore(File file, Logger logger) {
        if (!file.isFile()) return;

        Gson gson = new Gson();
        int restored = 0;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null && restored < this.capacity) {
                if (line.isEmpty()) continue;
                try {
                    // a torn line from a crash must not break a whole batch later
                    gson.fromJson(line, JsonObject.class);
                }
                catch (JsonParseException exception) {
                    continue;
                }
                if (this.offer(LogEvent.raw(line))) restored++;
            }
        }
        catch (IOException exception) {
            logger.warning("Не удалось прочитать " + file.getName() + ": " + exception.getMessage());
            return;
        }
        if (!file.delete()) {
            logger.warning("Не удалось удалить " + file.getName() + ", события из него могут уйти повторно.");
        }
        if (restored > 0) logger.info("Восстановлено событий с прошлого запуска: " + restored);
    }
}
