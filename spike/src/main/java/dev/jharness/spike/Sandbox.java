package dev.jharness.spike;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.options.OptionDescriptor;

/**
 * Builds engines and contexts the way the product will (plan "Isolate context"). The policy, host
 * access, isolate mode and thread count are fixed here; every limit value comes from a
 * {@link SandboxConfig.Profile}.
 */
final class Sandbox {

    private Sandbox() {}

    static Engine newEngine(SandboxConfig.Profile profile) {
        return newEngine(profile, Map.of());
    }

    /** @param extra extra engine options for a probe (e.g. forcing memory protection), never removing a limit */
    static Engine newEngine(SandboxConfig.Profile profile, Map<String, String> extra) {
        return newEngine(profile, extra, false);
    }

    /**
     * @param allowExperimental only for the memory-protection probe (AC-05): the option is experimental
     *     in polyglot 25.0. Product engines never enable experimental options.
     */
    static Engine newEngine(SandboxConfig.Profile profile, Map<String, String> extra, boolean allowExperimental) {
        Engine engine = Engine.newBuilder("js")
                .allowExperimentalOptions(allowExperimental)
                .sandbox(SandboxPolicy.UNTRUSTED)
                // No polyglot.* system properties: options come only from SandboxConfig.
                .useSystemProperties(false)
                .out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream())
                .option("engine.SpawnIsolate", "true")
                // engine.IsolateMode is experimental in polyglot 25.0 and needs allowExperimentalOptions.
                // Its default is "internal", so it is not set; requireInternalIsolateMode checks the default.
                .options(profile.engineOptions())
                .options(extra)
                .build();
        try {
            requireInternalIsolateMode(engine);
        } catch (RuntimeException e) {
            engine.close();
            throw e;
        }
        return engine;
    }

    /** Fails if the isolate mode this engine runs with would not be {@code internal} (Article I). */
    static void requireInternalIsolateMode(Engine engine) {
        OptionDescriptor d = engine.getOptions().get("engine.IsolateMode");
        Object mode = d == null ? null : d.getKey().getDefaultValue();
        if (!"internal".equals(String.valueOf(mode))) {
            throw new IllegalStateException("engine.IsolateMode default is " + mode + ", expected internal");
        }
    }

    static Context newContext(Engine engine, SandboxConfig.Profile profile, OutputStream out, OutputStream err) {
        Context ctx = Context.newBuilder("js")
                .engine(engine)
                .sandbox(SandboxPolicy.UNTRUSTED)
                .allowHostAccess(HostAccess.UNTRUSTED)
                .in(InputStream.nullInputStream())
                .out(out)
                .err(err)
                .option("sandbox.MaxThreads", "1")
                .options(profile.contextOptions())
                .build();
        ctx.getBindings("js").putMember("api", new SpikeApi());
        return ctx;
    }

    static Context newContext(Engine engine, SandboxConfig.Profile profile) {
        return newContext(engine, profile, OutputStream.nullOutputStream(), OutputStream.nullOutputStream());
    }
}
