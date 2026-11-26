package dev.jharness.cli;

import java.util.Map;

import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;

/**
 * JLine's line reader with a hook before it handles a resize while it reads (spec 058): JLine replaces the terminal's
 * WINCH handler during {@code readLine}, so the TUI's own handler doesn't run then.
 */
final class TuiReader extends LineReaderImpl {

    volatile Runnable beforeResize;

    TuiReader(Terminal terminal, String appName, Map<String, Object> variables) {
        super(terminal, appName, variables);
    }

    @Override
    protected synchronized void handleSignal(Terminal.Signal signal) {
        Runnable r = beforeResize;
        if (signal == Terminal.Signal.WINCH && r != null) {
            r.run();
        }
        super.handleSignal(signal);
    }
}
