package dev.jharness.spike;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.integrallis.models.api.BackendConfiguration;
import com.integrallis.models.api.ModelPrompt;
import com.integrallis.models.api.SamplingOptions;
import com.integrallis.models.backend.purejava.PureJavaBackend;
import com.integrallis.models.runtime.GenerationMetrics;
import com.integrallis.models.runtime.InferencePipeline;
import com.integrallis.models.runtime.chat.ChatMessage;
import com.integrallis.models.runtime.chat.ChatTemplate;

import org.modeljars.ModelBackend;
import org.modeljars.ModelJar;
import org.modeljars.ModelJarRegistry;
import org.modeljars.ModelJarRuntime;
import org.modeljars.ModelJars;
import org.modeljars.ModelLoadOptions;

/**
 * {@code spike model [--coordinate C] [--prompt-tokens N] [--gen N] [--cache DIR] [--model-file F] [--offline]
 * [--backend java|rust]}.
 *
 * <ul>
 *   <li>open: the marker on the classpath, pure-Java backend (AC-06, T018/T019)
 *   <li>generate: a short prompt, greedy, up to {@code --gen} tokens (default 128) (AC-06)
 *   <li>prompt8k: prefill of a ~{@code --prompt-tokens} prompt (default 8192), then 64 tokens,
 *       prefill and decode timed separately (AC-07, T020)
 *   <li>noMarker: open by coordinates and from the cache path; meaningful in a {@code -Dnomarker}
 *       build, where the marker JAR is absent (AC-08, T022)
 * </ul>
 *
 * {@code --backend rust} (T023, needs the {@code rust-backend} Maven profile) loads the same cached
 * weights with {@code RustFfmBackend} directly, bypassing the qualification check, so the speed
 * comparison uses identical weights.
 *
 * <p>Weights are downloaded by ModelJars itself on first use. That direct download is the spike's
 * recorded exception to constitution Article V (plan, constitution check).
 */
final class ModelProbe implements Main.Probe {

    private static final String NAME = "model";
    static final String DEFAULT_COORDINATE = "org.modeljars.huggingface:ggml-org.qwen3-0.6b-gguf.q4_0:3.0.0-q4_0.1";
    /** Prompt template the default model is qualified with. */
    static final String DEFAULT_TEMPLATE = "chatml-no-think";

    private static final String SNIPPET = """
            function fizzBuzz(n) {
              const out = [];
              for (let i = 1; i <= n; i++) {
                if (i % 15 === 0) out.push('FizzBuzz');
                else if (i % 3 === 0) out.push('Fizz');
                else if (i % 5 === 0) out.push('Buzz');
                else out.push(String(i));
              }
              return out.join('\\n');
            }
            """;

    @Override
    public boolean run(List<String> args, SandboxConfig config, JsonLines out) throws Exception {
        String coordinate = arg(args, "--coordinate", DEFAULT_COORDINATE);
        int promptTokens = Integer.parseInt(arg(args, "--prompt-tokens", "8192"));
        int gen = Integer.parseInt(arg(args, "--gen", "128"));
        boolean offline = args.contains("--offline");
        String cacheArg = arg(args, "--cache", null);
        Path cache = cacheArg != null ? Path.of(cacheArg) : null;

        boolean markerPresent = ModelJarRegistry.fromClasspath().resolve(ModelJar.of(coordinate)).isPresent();
        out.write(JsonLines.obj("probe", NAME, "check", "setup", "status", "pass", "coordinate", coordinate,
                "markerOnClasspath", markerPresent, "vectorModule",
                ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent(),
                "cpus", Runtime.getRuntime().availableProcessors(),
                "maxHeapMB", Runtime.getRuntime().maxMemory() >> 20));

        if (!markerPresent) {
            return noMarker(coordinate, cache, arg(args, "--model-file", null), out);
        }
        if ("rust".equals(arg(args, "--backend", "java"))) {
            return rust(cache, arg(args, "--model-file", null), gen, promptTokens, out);
        }

        ModelLoadOptions.Builder options = ModelLoadOptions.builder()
                .backend(ModelBackend.JAVA)
                .offline(offline)
                .maxContextLength(promptTokens + 1024);
        if (cache != null) {
            options.cacheDirectory(cache);
        }
        long t0 = System.nanoTime();
        try (ModelJarRuntime rt = ModelJars.openRuntime(ModelJar.of(coordinate), options.build())) {
            double openMs = ms(System.nanoTime() - t0);
            out.write(JsonLines.obj("probe", NAME, "check", "open", "status", "pass", "ms", openMs,
                    "backend", rt.pipeline().diagnostics().toString(),
                    "template", rt.chatTemplate().id(), "contextWindow", String.valueOf(rt.contextWindow())));
            boolean ok = generate(rt.pipeline(), rt.chatTemplate(), gen, out);
            ok &= prompt8k(rt.pipeline(), rt.chatTemplate(), promptTokens, out);
            return ok;
        } catch (RuntimeException e) {
            out.write(error("open", e));
            return false;
        }
    }

    private static boolean generate(InferencePipeline p, ChatTemplate template, int gen, JsonLines out) {
        try {
            ModelPrompt prompt = template.render(List.of(ChatMessage.user(
                    "Write a long, well-commented JavaScript module with five utility functions for strings.")));
            p.resetContext();
            String text = p.generate(prompt, greedy(gen));
            GenerationMetrics m = p.lastGenerationMetrics();
            out.write(JsonLines.obj("probe", NAME, "check", "generate", "status", "pass",
                    "maxTokens", gen, "metrics", metrics(m),
                    "text", text.length() > 400 ? text.substring(0, 400) + "..." : text));
            return true;
        } catch (RuntimeException e) {
            out.write(error("generate", e));
            return false;
        }
    }

    private static boolean prompt8k(InferencePipeline p, ChatTemplate template, int target, JsonLines out) {
        try {
            String header = "Here is some code. Summarize what it does in one sentence.\n\n";
            StringBuilder body = new StringBuilder();
            ModelPrompt prompt;
            int tokens;
            do {
                body.append(SNIPPET);
                prompt = template.render(List.of(ChatMessage.user(header + body)));
                tokens = p.tokenize(prompt).length;
            } while (tokens < target);
            p.resetContext();
            p.generate(prompt, greedy(64));
            GenerationMetrics m = p.lastGenerationMetrics();
            out.write(JsonLines.obj("probe", NAME, "check", "prompt8k", "status", "pass",
                    "promptTokens", tokens, "metrics", metrics(m)));
            return true;
        } catch (RuntimeException e) {
            out.write(error("prompt8k", e));
            return false;
        }
    }

    /** T023: the Rust FFM kernels on the same cached weights, loaded reflectively so the default build needs no backend-native. */
    private static boolean rust(Path cache, String modelFile, int gen, int promptTokens, JsonLines out) {
        Path root = cache != null ? cache : defaultCache();
        Optional<Path> file = modelFile != null ? Optional.of(Path.of(modelFile)) : onlyGguf(root);
        if (file.isEmpty()) {
            out.write(JsonLines.obj("probe", NAME, "check", "rustOpen", "status", "error",
                    "error", "no single .gguf under " + root + "; pass --model-file"));
            return false;
        }
        long t0 = System.nanoTime();
        try {
            Class<?> c = Class.forName("com.integrallis.models.backend.nativekernel.RustFfmBackend");
            com.integrallis.models.api.InferenceBackend backend = (com.integrallis.models.api.InferenceBackend)
                    c.getMethod("load", Path.class).invoke(null, file.get());
            try (InferencePipeline p = new InferencePipeline(backend)) {
                out.write(JsonLines.obj("probe", NAME, "check", "rustOpen", "status", "pass",
                        "ms", ms(System.nanoTime() - t0), "path", file.get().toString(),
                        "backend", p.diagnostics().toString()));
                ChatTemplate template = ChatTemplate.parse(DEFAULT_TEMPLATE);
                boolean ok = generate(p, template, gen, out);
                ok &= prompt8k(p, template, promptTokens, out);
                return ok;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : e;
            out.write(JsonLines.obj("probe", NAME, "check", "rustOpen", "status", "error",
                    "error", cause.getClass().getName() + ": " + cause.getMessage()));
            return false;
        }
    }

    /** AC-08: without the marker JAR, by coordinates (expected to fail) and then from the cache path. */
    private static boolean noMarker(String coordinate, Path cache, String modelFile, JsonLines out) {
        Map<String, Object> byCoordinates = JsonLines.obj("check", "byCoordinates");
        try (ModelJarRuntime rt = ModelJars.openRuntime(ModelJar.of(coordinate),
                ModelLoadOptions.builder().backend(ModelBackend.JAVA).offline(true).build())) {
            byCoordinates.put("outcome", "opened");
        } catch (RuntimeException e) {
            byCoordinates.put("outcome", "error");
            byCoordinates.put("error", e.getClass().getName() + ": " + e.getMessage());
        }

        Map<String, Object> fromCache = JsonLines.obj("check", "fromCachePath");
        Path root = cache != null ? cache : defaultCache();
        // The cache stores weights as <root>/sha256/<aa>/<sha256>/model.gguf: without the marker, the
        // SHA-256 that names the folder is unknown, so take --model-file or the only GGUF in the cache.
        Optional<Path> file = modelFile != null ? Optional.of(Path.of(modelFile)) : onlyGguf(root);
        fromCache.put("cacheRoot", root.toString());
        if (file.isEmpty()) {
            fromCache.put("outcome", "error");
            fromCache.put("error", "no single .gguf under the cache; pass --model-file or run the marker build first");
        } else {
            fromCache.put("path", file.get().toString());
            try (InferencePipeline p = new InferencePipeline(
                    PureJavaBackend.loadAutomatic(file.get(), BackendConfiguration.empty()))) {
                ChatTemplate template = ChatTemplate.parse(DEFAULT_TEMPLATE);
                String text = p.generate(template.render(List.of(ChatMessage.user("Say hello."))), greedy(16));
                fromCache.put("outcome", "opened");
                fromCache.put("text", text);
                fromCache.put("note", "PureJavaBackend.loadAutomatic on the cached file; no qualification check");
            } catch (RuntimeException e) {
                fromCache.put("outcome", "error");
                fromCache.put("error", e.getClass().getName() + ": " + e.getMessage());
            }
        }
        boolean pass = "opened".equals(fromCache.get("outcome"));
        out.write(JsonLines.obj("probe", NAME, "check", "noMarker", "status", pass ? "pass" : "recorded",
                "attempts", List.of(byCoordinates, fromCache)));
        return true;
    }

    // ---------------------------------------------------------------------------------------------

    private static SamplingOptions greedy(int maxTokens) {
        return SamplingOptions.builder().temperature(0f).repetitionPenalty(1f).maxTokens(maxTokens).build();
    }

    private static Map<String, Object> metrics(GenerationMetrics m) {
        int prompt = m.usage().promptTokens();
        int completion = m.usage().completionTokens();
        double prefillS = seconds(m.prefill());
        double decodeS = seconds(m.decode());
        return JsonLines.obj(
                "promptTokens", prompt, "completionTokens", completion,
                "cachedPromptTokens", m.promptCache().cacheReadInputTokens(),
                "prefillS", round(prefillS), "decodeS", round(decodeS), "totalS", round(seconds(m.total())),
                "timeToFirstTokenS", m.timeToFirstToken().map(d -> round(seconds(d))).orElse(null),
                "prefillTokensPerS", prefillS > 0 ? round(prompt / prefillS) : null,
                "decodeTokensPerS", round(m.decodeTokensPerSecond()),
                "stopReason", m.stopReason().map(Object::toString).orElse(null));
    }

    private static Path defaultCache() {
        String prop = System.getProperty("modeljars.cache");
        if (prop != null) {
            return Path.of(prop);
        }
        String env = System.getenv("MODELJARS_CACHE");
        return env != null ? Path.of(env) : Path.of(System.getProperty("user.home"), ".modeljars", "cache");
    }

    private static Optional<Path> onlyGguf(Path root) {
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (Stream<Path> s = Files.walk(root, 6)) {
            List<Path> found = s.filter(f -> f.getFileName().toString().endsWith(".gguf")).toList();
            return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Map<String, Object> error(String check, Exception e) {
        return JsonLines.obj("probe", NAME, "check", check, "status", "error",
                "error", e.getClass().getName() + ": " + e.getMessage());
    }

    private static String arg(List<String> args, String flag, String fallback) {
        int i = args.indexOf(flag);
        return i >= 0 && i + 1 < args.size() ? args.get(i + 1) : fallback;
    }

    private static double seconds(Duration d) {
        return d.toNanos() / 1e9;
    }

    private static double ms(long nanos) {
        return round(nanos / 1e6);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
