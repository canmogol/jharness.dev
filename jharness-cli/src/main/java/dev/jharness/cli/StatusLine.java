package dev.jharness.cli;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import dev.jharness.agent.Session;
import dev.jharness.events.Event;
import dev.jharness.events.Json;
import dev.jharness.events.Origin;

/**
 * The status line under the input (spec 042, D-080): refreshed after the events that change it, at most every
 * {@link #MIN_GAP_MS}, never for streamed chunks. A refresh reads {@link StatusState}, runs the user's script if
 * there is one, and hands the text to the painter, which draws it without racing other output.
 */
final class StatusLine implements AutoCloseable {

    static final long MIN_GAP_MS = 250;

    private final Session session;
    private final List<String> items;
    private final StatusScript script;
    private final Consumer<String> painter;
    private final Consumer<String> notice;
    final StatusState state = new StatusState();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jharness-status");
        t.setDaemon(true);
        return t;
    });
    private boolean scheduled;
    private long lastDraw;
    private String lastFailure;
    private volatile boolean closed;

    /**
     * @param painter draws one line of text (already escaped)
     * @param notice prints a one-off line above the prompt, for a script failure
     */
    StatusLine(Session session, List<String> items, StatusScript script, Consumer<String> painter, Consumer<String> notice) {
        this.session = session;
        this.items = List.copyOf(items);
        this.script = script;
        this.painter = painter;
        this.notice = notice;
        state.profile = session.profile();
        state.provider = session.loop().provider().provider();
        state.model = session.loop().provider().model();
        var c = session.loop().context();
        state.contextTokens = c.tokens();
        state.contextWindow = c.window();
        state.compactAt = c.threshold();
        state.workspace = session.workspace().root().getFileName() == null ? session.workspace().root().toString()
                : session.workspace().root().getFileName().toString();
        state.mode = session.permissionMode();
        state.plan = state.mode.equals("plan");
        var ready = Json.object().put("from", script != null ? "script" : "default");
        var a = ready.putArray("items");
        items.forEach(a::add);
        session.bus().publish(Origin.main(0), "statusline.ready", ready);
    }

    /** From the TUI's event handler: updates the values and asks for a refresh when they changed. */
    void onEvent(Event e) {
        if (e.type().startsWith("statusline.")) {
            return;
        }
        if (state.apply(e)) {
            request();
        }
    }

    /** Asks for a refresh: now, or when {@link #MIN_GAP_MS} has passed since the last one. */
    synchronized void request() {
        if (scheduled || closed) {
            return;
        }
        scheduled = true;
        long wait = Math.max(0, lastDraw + MIN_GAP_MS - System.currentTimeMillis());
        timer.schedule(this::refresh, wait, TimeUnit.MILLISECONDS);
    }

    /** A refresh on the caller's thread, before the prompt shows, so the line is current when the user types. */
    void refreshNow() {
        synchronized (this) {
            lastDraw = System.currentTimeMillis();
        }
        if (!closed) {
            try {
                painter.accept(render());
            } catch (RuntimeException e) {
                // As in refresh().
            }
        }
    }

    private void refresh() {
        synchronized (this) {
            scheduled = false;
            lastDraw = System.currentTimeMillis();
        }
        if (closed) {
            return;
        }
        try {
            painter.accept(render());
        } catch (RuntimeException e) {
            // A failed draw must never end the session; the next refresh draws again.
        }
    }

    /** The line: the script's, or the built-in one when there is no script or it failed. */
    String render() {
        String mode = session.permissionMode();
        synchronized (state) {
            state.mode = mode;
            state.plan = mode.equals("plan");
        }
        String line = TerminalSafe.escape(state.line(items));
        if (script == null || script.off() && lastFailure != null) {
            return line;
        }
        StatusScript.Outcome o = script.run(state.snapshot(items));
        if (o.ok()) {
            lastFailure = null;
            return o.line();
        }
        String key = o.reason() + ": " + o.error();
        if (!key.equals(lastFailure)) {
            lastFailure = key;
            session.bus().publish(Origin.main(state.turn), "statusline.failed", Json.object().put("reason", o.reason()).put("error", o.error()));
            notice.accept("Status line script failed (" + o.reason() + "): " + o.error() + "; showing the default line");
        }
        return line;
    }

    @Override
    public void close() {
        closed = true;
        timer.shutdownNow();
        if (script != null) {
            script.close();
        }
    }
}
