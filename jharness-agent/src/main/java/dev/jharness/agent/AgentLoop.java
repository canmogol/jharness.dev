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
import dev.jharness.sandbox.RunResult;

/**
 * The agent loop (D-007): stream a reply, run its first {@code js} block in the agent's isolate, send
 * the result back as the next user message, and repeat until a reply has no code, the step limit is
 * reached, or the model fails.
 */
public final class AgentLoop {

    static final String RESULT_PREFIX = "Result of your code:\n";

    private volatile ModelProvider provider;
    private final AgentIsolate isolate;
    private final ApiServices services;
    private final EventBus bus;
    private final TurnCounter turns;
    private final String system;
    private final int maxSteps;
    private final int resultChars;
    private final List<ChatMessage> conversation = new ArrayList<>();

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
        bus.publish(turns.get(), "turn.started", Json.object().put("prompt", prompt));
        conversation.add(ChatMessage.user(prompt));
        int steps = 0;
        String last = "";
        while (true) {
            ChatReply reply;
            try {
                reply = provider.stream(new ChatRequest(system, conversation, 0), onDelta);
            } catch (ModelException e) {
                return end(new TurnResult(last, "error", steps, e.getMessage()));
            }
            last = reply.text();
            conversation.add(ChatMessage.assistant(last));
            Optional<String> code = JsBlocks.first(last);
            if (code.isEmpty()) {
                return end(new TurnResult(last, "done", steps, null));
            }
            if (steps >= maxSteps) {
                return end(new TurnResult(last, "max_steps", steps, "the turn reached agent.maxSteps (" + maxSteps + ")"));
            }
            steps++;
            bus.publish(turns.get(), "loop.code", Json.object().put("step", steps).put("lines", code.get().split("\n", -1).length));
            RunResult r = isolate.run(code.get(), services);
            var result = Json.object().put("step", steps).put("status", r.status());
            if (r.limit() != null) {
                result.put("limit", r.limit());
            }
            bus.publish(turns.get(), "loop.result", result);
            conversation.add(ChatMessage.user(RESULT_PREFIX + cap(forModel(r))));
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
