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

    /** Spec 067: the prompt to draw after a resize, whose line above the input follows the new width; null keeps it. */
    volatile java.util.function.Supplier<String> promptOnResize;

    TuiReader(Terminal terminal, String appName, Map<String, Object> variables) {
        super(terminal, appName, variables);
    }

    @Override
    protected synchronized void handleSignal(Terminal.Signal signal) {
        Runnable r = beforeResize;
        if (signal == Terminal.Signal.WINCH && r != null) {
            r.run();
        }
        java.util.function.Supplier<String> p = promptOnResize;
        if (signal == Terminal.Signal.WINCH && p != null && isReading()) {
            setPrompt(p.get());
        }
        super.handleSignal(signal);
    }
}
