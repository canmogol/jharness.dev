package dev.jharness.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import dev.jharness.config.Settings;
import dev.jharness.events.EventBus;
import dev.jharness.events.Json;
import dev.jharness.sandbox.AgentIsolate;
import dev.jharness.sandbox.ApiServices;
import dev.jharness.sandbox.IsolateManager;
import dev.jharness.sandbox.RunResult;

/**
 * Slash commands (spec 035, D-074): {@code NAME.md} prompt templates and {@code NAME.js} commands in
 * {@code ~/.jharness/commands/} and, in a trusted project, {@code .jharness/commands/}. A template's text,
 * with {@code $ARGUMENTS} replaced, becomes the prompt. A JS command's {@code run(args)} returns a prompt
 * string, {@code {prompt}}, {@code {print}} or nothing; it runs in its own isolate with the subagent
 * limits, and its {@code api} calls go through the gate and the hooks.
 */
public final class CommandRunner {

    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    static final long MAX_FILE_BYTES = 64 * 1024;
    static final Pattern FRONT = Pattern.compile("\\A---\\n(.*?)\\n---\\n?", Pattern.DOTALL);

    /**
     * One command. {@code kind} is {@code md} or {@code js}.
     *
     * @param args the choices Tab offers for its argument (spec 041), from {@code args:} or a JS {@code args} array
     */
    public record Command(String name, String source, String kind, Path file, String description, String body, List<String> args) {

        public Command {
            args = List.copyOf(args);
        }
    }

    /** A command file named like a built-in command or alias, which wins (spec 041). */
    public record Clash(String file, String name, String source) {}

    static final int MAX_ARGS = 50;
    static final Pattern ARG = Pattern.compile("[^\\s]{1,60}");

    /** What a command gives: a prompt to send to the agent, text to show, or nothing. */
    public record Expansion(String prompt, String print) {}

    private final Map<String, Command> commands = new TreeMap<>();
    private final Map<String, String> errors = new TreeMap<>();
    private final List<Clash> clashes = new ArrayList<>();
    private final Settings settings;
    private final EventBus bus;
    private final TurnCounter turns;
    private volatile ApiServices services;

    private CommandRunner(Settings settings, EventBus bus, TurnCounter turns) {
        this.settings = settings;
        this.bus = bus;
        this.turns = turns;
    }

    static Path userFolder(Path home) {
        return home.resolve(".jharness").resolve("commands");
    }

    static Path projectFolder(Path workspace) {
        return workspace.resolve(".jharness").resolve("commands");
    }

    /**
     * @param builtIn the TUI's own command names, which a file can't take
     */
    static CommandRunner load(Path home, Path workspace, boolean trusted, Set<String> builtIn, Settings settings, EventBus bus,
            TurnCounter turns, ApiServices services) {
        CommandRunner r = new CommandRunner(settings, bus, turns);
        r.services = services;
        r.read(userFolder(home), "user", builtIn);
        if (trusted) {
            r.read(projectFolder(workspace), "project", builtIn);
        }
        var e = Json.object();
        var names = e.putArray("commands");
        r.commands.values().forEach(c -> names.addObject().put("name", c.name()).put("source", c.source()).put("kind", c.kind()));
        if (!r.errors.isEmpty()) {
            var errs = e.putObject("errors");
            r.errors.forEach(errs::put);
        }
        if (!r.clashes.isEmpty()) {
            var cl = e.putArray("shadowed");
            r.clashes.forEach(c -> cl.add(c.file()));
        }
        bus.publish(turns.get(), "commands.loaded", e);
        return r;
    }

    private void read(Path dir, String source, Set<String> builtIn) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> files;
        try (var s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().matches(".+\\.(md|js)")).sorted().toList();
        } catch (IOException e) {
            return;
        }
        for (Path f : files) {
            String fn = f.getFileName().toString();
            String kind = fn.endsWith(".md") ? "md" : "js";
            String name = fn.substring(0, fn.length() - kind.length() - 1);
            String shown = (source.equals("user") ? "~/.jharness/commands/" : ".jharness/commands/") + fn;
            try {
                if (!NAME.matcher(name).matches()) {
                    throw new IOException("command names use lower-case letters, digits and '-', starting with a letter");
                }
                if (builtIn.contains("/" + name)) {
                    clashes.add(new Clash(shown, name, source));
                    bus.publish(turns.get(), "command.shadowed", Json.object().put("name", name).put("file", shown).put("from", source));
                    continue;
                }
                if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("must be a regular file, not a link");
                }
                if (Files.size(f) > MAX_FILE_BYTES) {
                    throw new IOException("is larger than 64 KB");
                }
                if (source.equals("user") && Files.getPosixFilePermissions(f, LinkOption.NOFOLLOW_LINKS).contains(PosixFilePermission.OTHERS_WRITE)) {
                    throw new IOException("others can write it; run chmod o-w on it");
                }
                String text = Files.readString(f, StandardCharsets.UTF_8);
                Command c = kind.equals("md") ? template(name, source, f, text) : script(name, source, f, text);
                Command before = commands.get(name);
                if (before != null && before.source().equals(source)) {
                    throw new IOException("/" + name + " is defined twice (" + before.file().getFileName() + " and " + fn + ")");
                }
                commands.put(name, c);
                errors.remove(name);
            } catch (IOException | RuntimeException e) {
                errors.put(shown, e.getMessage());
            }
        }
    }

    private static Command template(String name, String source, Path f, String text) throws IOException {
        String desc = "";
        List<String> args = new ArrayList<>();
        String body = text;
        Matcher m = FRONT.matcher(text.replace("\r\n", "\n"));
        if (m.find()) {
            for (String line : m.group(1).split("\n")) {
                if (line.startsWith("description:")) {
                    desc = line.substring("description:".length()).strip();
                } else if (line.startsWith("args:")) {
                    String v = line.substring("args:".length()).strip();
                    if (v.startsWith("[") && v.endsWith("]")) {
                        v = v.substring(1, v.length() - 1);
                    }
                    for (String a : v.split(",")) {
                        String x = a.strip();
                        if (x.length() >= 2 && (x.startsWith("\"") && x.endsWith("\"") || x.startsWith("'") && x.endsWith("'"))) {
                            x = x.substring(1, x.length() - 1);
                        }
                        if (!x.isEmpty()) {
                            args.add(x);
                        }
                    }
                }
            }
            body = text.replace("\r\n", "\n").substring(m.end());
        } else {
            String first = text.strip().lines().findFirst().orElse("");
            desc = first.length() > 80 ? first.substring(0, 80) + "…" : first;
        }
        return new Command(name, source, "md", f, desc, body, checkArgs(args));
    }

    /** A JS command: checked once in an isolate; its {@code description} variable, if any, describes it. */
    private Command script(String name, String source, Path f, String text) throws IOException {
        try (AgentIsolate iso = isolate(name)) {
            RunResult probe = iso.run(text + "\n;JSON.stringify([typeof run, typeof description === 'string' ? description : '', "
                    + "typeof args !== 'undefined' && Array.isArray(args) ? args.map(String) : []])", services);
            if (!probe.ok()) {
                throw new IOException("doesn't run: " + (probe.error() != null ? probe.error() : probe.status()));
            }
            JsonNode v = parse(probe.value());
            if (!v.get(0).asText().equals("function")) {
                throw new IOException("defines no run(args) function");
            }
            List<String> args = new ArrayList<>();
            v.get(2).forEach(a -> args.add(a.asText()));
            return new Command(name, source, "js", f, v.get(1).asText(), text, checkArgs(args));
        }
    }

    private AgentIsolate isolate(String name) {
        return new IsolateManager(settings.sandbox(), bus, TurnCounter.following("command-" + name, turns)).create("command-" + name, "subagent");
    }

    private static JsonNode parse(String value) {
        return Json.parse(value);
    }

    public Map<String, Command> commands() {
        return Map.copyOf(commands);
    }

    /** Command files hidden by a built-in of the same name. */
    public List<Clash> clashes() {
        return List.copyOf(clashes);
    }

    private static List<String> checkArgs(List<String> args) throws IOException {
        if (args.size() > MAX_ARGS) {
            throw new IOException("args lists more than " + MAX_ARGS + " choices");
        }
        for (String a : args) {
            if (!ARG.matcher(a).matches()) {
                throw new IOException("args: each choice is one word of up to 60 characters, not \"" + a + "\"");
            }
        }
        return List.copyOf(new java.util.LinkedHashSet<>(args));
    }

    public Map<String, String> errors() {
        return Map.copyOf(errors);
    }

    public Command get(String name) {
        return commands.get(name);
    }

    /** Expands {@code /name args}; throws with a message the user can read when the command fails. */
    public Expansion run(String name, String args) {
        Command c = commands.get(name);
        if (c == null) {
            throw new IllegalArgumentException("no command /" + name);
        }
        long t0 = System.nanoTime();
        Expansion x;
        try {
            x = c.kind().equals("md") ? new Expansion(c.body().replace("$ARGUMENTS", args).strip(), null) : runScript(c, args);
        } catch (RuntimeException e) {
            bus.publish(turns.get(), "command.ran", Json.object().put("name", name).put("from", c.source()).put("kind", c.kind())
                    .put("outcome", "error").put("error", String.valueOf(e.getMessage())));
            throw e;
        }
        bus.publish(turns.get(), "command.ran", Json.object().put("name", name).put("from", c.source()).put("kind", c.kind())
                .put("outcome", x.prompt() != null ? "prompt" : x.print() != null ? "print" : "none")
                .put("durationMs", (System.nanoTime() - t0) / 1_000_000));
        return x;
    }

    private Expansion runScript(Command c, String args) {
        String js = c.body() + "\n;(function () { const r = run(" + Json.write(Json.MAPPER.getNodeFactory().textNode(args))
                + "); return r === undefined || r === null ? 'null' : JSON.stringify(r); })()";
        RunResult r;
        try (AgentIsolate iso = isolate(c.name())) {
            r = iso.run(js, services);
        }
        if (!r.ok()) {
            throw new IllegalStateException("/" + c.name() + " failed: " + (r.error() != null ? r.error() : r.status()));
        }
        JsonNode v = parse(r.value());
        if (v.isTextual()) {
            return new Expansion(v.asText(), null);
        }
        if (v.isObject() && v.hasNonNull("prompt")) {
            return new Expansion(v.get("prompt").asText(), null);
        }
        if (v.isObject() && v.hasNonNull("print")) {
            return new Expansion(null, v.get("print").asText());
        }
        return new Expansion(null, null);
    }

    /** {@code /commands} and {@code /help}: one line per command. */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Command c : commands.values()) {
            out.add(String.format("  %-15s %s%s", "/" + c.name(), c.description().isEmpty() ? "(" + c.kind() + ")" : c.description(),
                    c.source().equals("project") ? " [project]" : ""));
        }
        return out;
    }
}
