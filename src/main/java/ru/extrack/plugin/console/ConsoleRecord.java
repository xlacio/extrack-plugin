package ru.extrack.plugin.console;

import com.google.gson.stream.JsonWriter;
import java.io.IOException;

/** One WARN or ERROR line from the server console. */
public final class ConsoleRecord {

    private static final int MAX_MESSAGE = 20_000;
    private static final int MAX_TRACE   = 100_000;

    private final long   time;
    private final String level;
    private final String logger;
    private final String thread;
    private final String message;
    private final String throwable;

    ConsoleRecord(long time, String level, String logger, String thread, String message, String throwable) {
        this.time = time;
        this.level = level;
        this.logger = logger;
        this.thread = thread;
        this.message = message;
        this.throwable = throwable;
    }

    public void write(JsonWriter out) throws IOException {
        out.beginObject();
        out.name("time").value(this.time);
        out.name("level").value(this.level);
        if (this.logger != null) out.name("logger").value(cut(this.logger, 200));
        if (this.thread != null) out.name("thread").value(cut(this.thread, 200));
        out.name("message").value(cut(this.message, MAX_MESSAGE));
        if (this.throwable != null) out.name("throwable").value(cut(this.throwable, MAX_TRACE));
        out.endObject();
    }

    private static String cut(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }
}
