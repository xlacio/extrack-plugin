package ru.extrack.plugin.console;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Logger;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.filter.ThresholdFilter;

/**
 * Log4j2 appender for WARN and above. Paper writes the whole console through Log4j2, so this sees
 * plugin errors, server warnings and stack traces alike. Runs on whatever thread logged the line.
 */
public final class ConsoleCapture extends AbstractAppender {

    private static final int MAX_TRACE_LINES = 120;

    private final ConsoleQueue queue;
    private final String       pluginName;
    private final String       loggerName;
    private final String       prefix;

    @SuppressWarnings("deprecation")
    private ConsoleCapture(ConsoleQueue queue, String pluginName, String loggerName) {
        // the four argument constructor exists in every Log4j 2 Paper has shipped, 2.8 included
        super("ExTrackConsole", ThresholdFilter.createFilter(Level.WARN, Filter.Result.ACCEPT, Filter.Result.DENY), null, false);
        this.queue = queue;
        this.pluginName = pluginName;
        this.loggerName = loggerName;
        this.prefix = "[" + pluginName + "]";
    }

    /** @return null if the server does not use Log4j2 core for its console */
    public static ConsoleCapture attach(ConsoleQueue queue, String pluginName, Logger pluginLogger) {
        try {
            ConsoleCapture capture = new ConsoleCapture(queue, pluginName, pluginLogger.getName());
            capture.start();
            ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addAppender(capture);
            return capture;
        }
        catch (ClassCastException | LinkageError exception) {
            pluginLogger.warning("Перехват ошибок консоли недоступен на этом сервере: " + exception.getMessage());
            return null;
        }
    }

    public void detach() {
        try {
            ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).removeAppender(this);
        }
        finally {
            this.stop();
        }
    }

    @Override
    public void append(LogEvent event) {
        if (this.queue.isFull()) return;
        // our own warnings about a lost connection must not feed back into the panel,
        // depending on the version Paper names plugin loggers after the plugin or after its main class
        String logger = event.getLoggerName();
        if (this.pluginName.equals(logger) || this.loggerName.equals(logger)) return;
        String message = event.getMessage() == null ? null : event.getMessage().getFormattedMessage();
        if (message == null) message = "";
        if (message.startsWith(this.prefix)) return;
        this.queue.offer(new ConsoleRecord(event.getTimeMillis(), event.getLevel().name(), logger,
            event.getThreadName(), message, trace(event.getThrown())));
    }

    private static String trace(Throwable throwable) {
        if (throwable == null) return null;
        StringWriter out = new StringWriter(2048);
        throwable.printStackTrace(new PrintWriter(out));
        String text = out.toString();
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && ++lines == MAX_TRACE_LINES) {
                return text.substring(0, i) + "\n\t... trimmed";
            }
        }
        return text;
    }
}
