package dev.jharness.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.llm.ChatMessage;
import dev.jharness.llm.ChatReply;
import dev.jharness.llm.ChatRequest;
import dev.jharness.llm.ModelException;
import dev.jharness.llm.ModelProvider;
import dev.jharness.sandbox.AgentIsolate;
import dev.jharness.sandbox.ApiServices;
import dev.jharness.sandbox.Checkpoints;
import dev.jharness.sandbox.RunResult;

/**
 * The agent loop (D-007): stream a reply, run its first {@code js} block in the agent's isolate, send
 * the result back as the next user message, and repeat until a reply has no code, the step limit is
 * reached, or the model fails.
 */
public final class AgentLoop {

    static final String RESULT_PREFIX = "Result of your code:\n";
    /** Longest error text put on {@code loop.result} (spec 009 AC-03). */
    static final int ERROR_CHARS = 2000;

    private volatile ModelProvider provider;
    private final AgentIsolate isolate;
    private final ApiServices services;
    private final EventBus bus;
    private final TurnCounter turns;
    private final String system;
    private final int maxSteps;
    private final int resultChars;
    private final List<ChatMessage> conversation = new ArrayList<>();
    /** Turns still in the conversation, oldest first, with the conversation size when each started. */
    private final java.util.TreeMap<Integer, Integer> liveTurns = new java.util.TreeMap<>();
    private Checkpoints checkpoints;
    private volatile boolean interrupted;
    private volatile Thread turnThread;

    /** Esc (US-2.5): stops the model stream and the running block; the turn ends with {@code interrupted}. */
    public void interrupt() {
        interrupted = true;
        ModelProvider p = provider;
        p.cancel();
        isolate.cancel();
        Thread t = turnThread;
        if (t != null) {
            t.interrupt();
        }
    }

    public void useCheckpoints(Checkpoints c) {
        checkpoints = c;
    }

    /** {@code /diff}: the last turn's file changes as unified diffs. */
    public synchronized String lastTurnDiff() {
        if (checkpoints == null || liveTurns.isEmpty()) {
            return "";
        }
        return checkpoints.diff(liveTurns.lastKey());
    }

    /** The turns that {@code /rewind} can go back to. */
    public synchronized List<Integer> turns() {
        return List.copyOf(liveTurns.keySet());
    }

    /** {@code /undo}: restores the last turn's files and removes the turn from the conversation (D-044). */
    public synchronized Checkpoints.Restore undo(boolean force) {
        if (liveTurns.isEmpty()) {
            return new Checkpoints.Restore(true, List.of(), List.of(), List.of(), List.of(), List.of());
        }
        return rewind(liveTurns.lastKey() - 1, force);
    }

    /** {@code /rewind N}: undoes every turn after turn {@code n}. */
    public synchronized Checkpoints.Restore rewind(int n, boolean force) {
        var after = liveTurns.tailMap(n, false);
        if (after.isEmpty()) {
            return new Checkpoints.Restore(true, List.of(), List.of(), List.of(), List.of(), List.of());
        }
        Checkpoints.Restore r = checkpoints != null ? checkpoints.restore(n, force)
                : new Checkpoints.Restore(true, List.of(), List.of(), List.of(), List.of(), List.copyOf(after.keySet()));
        if (r.done()) {
            int cut = after.firstEntry().getValue();
            List<Integer> undone = List.copyOf(after.keySet());
            while (conversation.size() > cut) {
                conversation.removeLast();
            }
            after.clear();
            return new Checkpoints.Restore(true, r.restored(), r.conflicts(), r.notRestorable(), r.gitOps(), undone);
        }
        return r;
    }

    public AgentLoop(ModelProvider provider, AgentIsolate isolate, ApiServices services, EventBus bus, TurnCounter turns,
            String system, int maxSteps, int resultChars) {
        this.provider = provider;
        this.isolate = isolate;
        this.services = services;
        this.bus = bus;
        this.turns = turns;
        this.system = system;
        this.maxSteps = maxSteps;
        this.resultChars = resultChars;
    }

    /** Switches the model between turns (and before the next step of a running turn). */
    public void useProvider(ModelProvider next) {
        provider = next;
    }

    public ModelProvider provider() {
        return provider;
    }

    public synchronized List<ChatMessage> conversation() {
        return List.copyOf(conversation);
    }

    public String systemPrompt() {
        return system;
    }

    /** One user turn. Never throws for model or guest problems; they end the turn with a stop reason. */
    public synchronized TurnResult turn(String prompt, Consumer<String> onDelta) {
        turns.next();
        interrupted = false;
        turnThread = Thread.currentThread();
        try {
            return runTurn(prompt, onDelta);
        } finally {
            turnThread = null;
            Thread.interrupted();
        }
    }

    private TurnResult runTurn(String prompt, Consumer<String> onDelta) {
        bus.publish(turns.get(), "turn.started", Json.object().put("prompt", prompt));
        liveTurns.put(turns.current(), conversation.size());
        conversation.add(ChatMessage.user(prompt));
        int steps = 0;
        String last = "";
        while (true) {
            ChatReply reply;
            try {
                reply = provider.stream(new ChatRequest(system, conversation, 0), onDelta);
            } catch (ModelException e) {
                if (interrupted) {
                    return end(new TurnResult(last, "interrupted", steps, null));
                }
                return end(new TurnResult(last, "error", steps, e.getMessage()));
            }
            if (interrupted) {
                conversation.add(ChatMessage.assistant(reply.text()));
                return end(new TurnResult(reply.text(), "interrupted", steps, null));
            }
            last = reply.text();
            conversation.add(ChatMessage.assistant(last));
            Optional<JsBlocks.Found> code = JsBlocks.find(last);
            if (code.isEmpty()) {
                return end(new TurnResult(last, "done", steps, null));
            }
            if (steps >= maxSteps) {
                return end(new TurnResult(last, "max_steps", steps, "the turn reached agent.maxSteps (" + maxSteps + ")"));
            }
            steps++;
            int ignored = code.get().ignored();
            var codeEvent = Json.object().put("step", steps).put("lines", code.get().code().split("\n", -1).length);
            if (ignored > 0) {
                codeEvent.put("ignoredBlocks", ignored);
            }
            bus.publish(turns.get(), "loop.code", codeEvent);
            RunResult r = isolate.run(code.get().code(), services);
            var result = Json.object().put("step", steps).put("status", r.status());
            if (r.limit() != null) {
                result.put("limit", r.limit());
            }
            if (!r.status().equals("ok")) {
                String err = r.error() != null ? r.error() : forModel(r);
                result.put("error", err.length() > ERROR_CHARS ? err.substring(0, ERROR_CHARS) + "…" : err);
                result.put("line", r.line());
            }
            bus.publish(turns.get(), "loop.result", result);
            if (interrupted) {
                conversation.add(ChatMessage.user(RESULT_PREFIX + "The user stopped this code with Esc."));
                return end(new TurnResult(last, "interrupted", steps, null));
            }
            conversation.add(ChatMessage.user(RESULT_PREFIX + cap(forModel(r)) + ignoredNote(ignored)));
        }
    }

    /** Adds the limit's name, so the model knows which limit stopped it (AC-02). */
    static String forModel(RunResult r) {
        String text = r.forModel();
        if (r.limit() != null && !text.contains(r.limit())) {
            text += "\n(stopped by the " + r.limit() + " limit)";
        }
        return text;
    }

    /** Tells the model that later blocks in its reply didn't run (spec 008); added after the cap so it is never cut. */
    static String ignoredNote(int ignored) {
        if (ignored == 0) {
            return "";
        }
        return "\n\nNote: your reply had " + ignored + " more js block(s) after the first; they did not run. "
                + "Send the next one in your next reply.";
    }

    /** Keeps the last {@code resultChars} characters, saying how much was cut. */
    String cap(String text) {
        if (text.length() <= resultChars) {
            return text;
        }
        return "[" + (text.length() - resultChars) + " characters cut]\n" + text.substring(text.length() - resultChars);
    }

    private TurnResult end(TurnResult r) {
        var done = Json.object().put("steps", r.steps()).put("stopReason", r.stopReason());
        if (r.error() != null) {
            done.put("error", r.error());
        }
        bus.publish(turns.get(), "turn.completed", done);
        return r;
    }
}
