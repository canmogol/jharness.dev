package dev.jharness.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

import dev.jharness.events.SessionLogs;
import dev.jharness.web.EventFeed;
import dev.jharness.web.LogTail;
import dev.jharness.web.SessionViewer;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * {@code jharness view [SESSION]} (spec 048, D-083): a read-only browser view of a saved or running session, from its
 * log, on {@code 127.0.0.1}. It follows the log as it grows until Ctrl-C.
 */
@Command(name = "view", mixinStandardHelpOptions = true,
        description = {"Show a session in the browser, read-only, on 127.0.0.1; it follows a running session's log.",
                "SESSION is an id, a unique prefix of one, \"last\" (the default), or a path to a .jsonl log. Ctrl-C stops it."})
final class ViewCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Parameters(arity = "0..1", paramLabel = "SESSION", description = "The session to show (default: last).")
    String session;

    @Option(names = "--cwd", paramLabel = "DIR", description = "Workspace folder (default: the current folder).")
    Path cwd;

    @Option(names = "--port", paramLabel = "PORT", description = "Port on 127.0.0.1 (default: any free port).")
    int port;

    @Option(names = "--home", hidden = true, paramLabel = "DIR", description = "Tests only: use DIR instead of the home folder.")
    Path home;

    /** Tests only: called once the viewer listens; the command returns when the latch counts down. */
    static volatile java.util.function.BiConsumer<SessionViewer, CountDownLatch> started;

    @Override
    public Integer call() {
        PrintStream out = parent.out;
        PrintStream err = parent.err;
        if (port < 0 || port > 65535) {
            err.println("jharness view: --port must be 0 to 65535");
            return 2;
        }
        Path h = home != null ? home : parent.home != null ? parent.home : Path.of(System.getProperty("user.home"));
        Path c = cwd != null ? cwd : parent.cwd;
        Path ws = (c != null ? c : Path.of("")).toAbsolutePath().normalize();
        Path file;
        try {
            file = SessionLogs.resolve(h, ws, session == null ? "last" : session);
        } catch (SessionLogs.LookupException | IOException | RuntimeException e) {
            err.println("jharness view: " + e.getMessage());
            return 2;
        }
        EventFeed feed = new EventFeed();
        CountDownLatch stop = new CountDownLatch(1);
        try (LogTail tail = LogTail.start(file, feed); SessionViewer v = SessionViewer.start(feed, port)) {
            out.println("Viewing " + TerminalSafe.escape(file.toString()));
            out.println("Open " + v.url() + " (read-only, this machine only; Ctrl-C stops)");
            out.flush();
            var hook = started;
            if (hook != null) {
                hook.accept(v, stop);
            }
            Thread shutdown = new Thread(stop::countDown);
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                stop.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException ignored) {
                // Already shutting down.
            }
            return 0;
        } catch (IOException e) {
            err.println("jharness view: " + e.getMessage());
            return 2;
        }
    }
}
