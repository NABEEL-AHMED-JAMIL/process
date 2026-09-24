package process.settings;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Every log event process writes while it is attached, at every level -- the root and the non-additive "process"
 * logger both, which logback.xml sends to the console and the file -- for tests that assert a value never reaches a
 * log (MIG-167). Messages, arguments and throwables all count.
 */
final class LogCapture implements AutoCloseable {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final List<Logger> loggers = new ArrayList<>();
    private final List<Level> levels = new ArrayList<>();

    LogCapture() {
        this.appender.start();
        for (String name : new String[] {Logger.ROOT_LOGGER_NAME, "process"}) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            this.loggers.add(logger);
            this.levels.add(logger.getLevel());
            logger.setLevel(Level.ALL);
            logger.addAppender(this.appender);
        }
    }

    String everything() {
        return this.appender.list.stream().map(LogCapture::render).collect(Collectors.joining("\n"));
    }

    private static String render(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        for (IThrowableProxy thrown = event.getThrowableProxy(); thrown != null; thrown = thrown.getCause()) {
            text.append(" | ").append(thrown.getClassName()).append(": ").append(thrown.getMessage());
        }
        return text.toString();
    }

    @Override
    public void close() {
        for (int i = 0; i < this.loggers.size(); i++) {
            this.loggers.get(i).detachAppender(this.appender);
            this.loggers.get(i).setLevel(this.levels.get(i));
        }
    }
}
