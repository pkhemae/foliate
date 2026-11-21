package dev.foliate.testing;

import java.io.Serial;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.MessageFormatter;

/** Keeps what is logged, formatted, so a test can check what an administrator would read. */
public final class RecordingLogger extends AbstractLogger {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient List<String> lines = new CopyOnWriteArrayList<>();

    public RecordingLogger() {
        this.name = "test";
    }

    /** Each entry is the level, a space and the formatted message. */
    public List<String> lines() {
        return List.copyOf(lines);
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return RecordingLogger.class.getName();
    }

    @Override
    protected void handleNormalizedLoggingCall(
            Level level,
            @Nullable Marker marker,
            String pattern,
            Object @Nullable [] arguments,
            @Nullable Throwable throwable) {
        lines.add(level + " " + MessageFormatter.arrayFormat(pattern, arguments).getMessage());
    }

    @Override
    public boolean isTraceEnabled() {
        return true;
    }

    @Override
    public boolean isTraceEnabled(Marker marker) {
        return true;
    }

    @Override
    public boolean isDebugEnabled() {
        return true;
    }

    @Override
    public boolean isDebugEnabled(Marker marker) {
        return true;
    }

    @Override
    public boolean isInfoEnabled() {
        return true;
    }

    @Override
    public boolean isInfoEnabled(Marker marker) {
        return true;
    }

    @Override
    public boolean isWarnEnabled() {
        return true;
    }

    @Override
    public boolean isWarnEnabled(Marker marker) {
        return true;
    }

    @Override
    public boolean isErrorEnabled() {
        return true;
    }

    @Override
    public boolean isErrorEnabled(Marker marker) {
        return true;
    }
}
