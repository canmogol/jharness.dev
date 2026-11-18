package dev.jharness.spike;

import java.util.ArrayList;
import java.util.List;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * {@code spike jline [--lines N]}: reads up to N lines (default 3) in raw mode with line editing and
 * in-memory history, ending early on Ctrl-D (AC-10). Needs a real terminal; checked by hand and by
 * {@code scripts/jline-pty-check.py}, which drives it through a pseudo-terminal.
 *
 * <p>The JSON result is written after the terminal is closed, so it never mixes with the prompt.
 */
final class JLineProbe implements Main.Probe {

    private static final String NAME = "jline";

    @Override
    public boolean run(List<String> args, SandboxConfig config, JsonLines out) throws Exception {
        int max = 3;
        int i = args.indexOf("--lines");
        if (i >= 0 && i + 1 < args.size()) {
            max = Integer.parseInt(args.get(i + 1));
        }
        List<String> lines = new ArrayList<>();
        String type;
        String provider;
        boolean dumb;
        String ending = "limit";
        int historySize;
        try (Terminal terminal = TerminalBuilder.builder().system(true).provider("ffm").dumb(false).build()) {
            type = terminal.getType();
            provider = terminal.getClass().getName();
            dumb = Terminal.TYPE_DUMB.equals(type) || Terminal.TYPE_DUMB_COLOR.equals(type);
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).appName("jharness-spike").build();
            while (lines.size() < max) {
                try {
                    lines.add(reader.readLine("spike> "));
                } catch (EndOfFileException e) {
                    ending = "ctrl-d";
                    break;
                } catch (UserInterruptException e) {
                    ending = "ctrl-c";
                    break;
                }
            }
            historySize = reader.getHistory().size();
        } catch (RuntimeException e) {
            out.write(JsonLines.obj("probe", NAME, "status", "error", "error", e.getClass().getName() + ": " + e.getMessage()));
            return false;
        }
        boolean pass = !dumb;
        out.write(JsonLines.obj("probe", NAME, "status", pass ? "pass" : "fail",
                "terminalType", type, "terminal", provider, "dumb", dumb,
                "lines", new ArrayList<Object>(lines), "historySize", historySize, "ending", ending));
        return pass;
    }
}
