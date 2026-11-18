package dev.jharness.sandbox;

import java.io.OutputStream;
import java.util.function.Supplier;

import org.graalvm.options.OptionDescriptor;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.SandboxPolicy;

import dev.jharness.config.SandboxSettings;
import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;

/**
 * Builds agent isolates (D-002, D-004): one engine per agent with {@code engine.SpawnIsolate} and the
 * profile's engine limits. The isolate mode is the engine default and is checked to be
 * {@code internal} (D-034). Memory protection follows D-036: {@code off} by default; {@code auto}
 * probes once per process and continues without it; {@code on} fails if the probe fails.
 */
public final class IsolateManager {

    static final String MEMORY_PROTECTION_OPTION = "engine.IsolateMemoryProtection";

    private static volatile Boolean protectionAvailable;

    private final SandboxSettings settings;
    private final EventBus bus;
    private final Supplier<Origin> origin;

    public IsolateManager(SandboxSettings settings, EventBus bus, Supplier<Origin> origin) {
        this.settings = settings;
        this.bus = bus;
        this.origin = origin;
    }

    /** A new isolate for an agent, using the sandbox profile {@code main} or {@code subagent}. */
    public AgentIsolate create(String agent, String profileName) {
        SandboxSettings.Profile p = settings.profile(profileName);
        boolean protect = switch (p.memoryProtection()) {
            case OFF -> false;
            case AUTO -> memoryProtectionAvailable(p);
            case ON -> {
                if (!memoryProtectionAvailable(p)) {
                    throw new IllegalStateException("engine.MemoryProtection is on, but memory protection keys are not available here");
                }
                yield true;
            }
        };
        return new AgentIsolate(agent, p, bus, origin, newEngine(p, protect), protect);
    }

    static Engine newEngine(SandboxSettings.Profile p, boolean memoryProtection) {
        Engine.Builder b = Engine.newBuilder("js")
                .sandbox(SandboxPolicy.UNTRUSTED)
                .useSystemProperties(false)
                .out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream())
                .option("engine.SpawnIsolate", "true")
                .options(p.engineOptions());
        if (memoryProtection) {
            // Experimental in polyglot 25.0; only the user's setting turns this on (D-036).
            b.allowExperimentalOptions(true).option(MEMORY_PROTECTION_OPTION, "true");
        }
        Engine e = b.build();
        try {
            requireInternalIsolateMode(e);
        } catch (RuntimeException ex) {
            e.close();
            throw ex;
        }
        return e;
    }

    /** Fails unless the engine's isolate mode is {@code internal} (constitution Article I). */
    static void requireInternalIsolateMode(Engine engine) {
        OptionDescriptor d = engine.getOptions().get("engine.IsolateMode");
        Object mode = d == null ? null : d.getKey().getDefaultValue();
        if (!"internal".equals(String.valueOf(mode))) {
            throw new IllegalStateException("engine.IsolateMode default is " + mode + ", expected internal");
        }
    }

    /** One probe per process: build an engine with protection on and run one context (D-036). */
    private synchronized boolean memoryProtectionAvailable(SandboxSettings.Profile p) {
        if (protectionAvailable == null) {
            boolean ok;
            String detail;
            try (Engine e = newEngine(p, true);
                    Context c = Context.newBuilder("js").engine(e).sandbox(SandboxPolicy.UNTRUSTED)
                            .allowHostAccess(HostAccess.UNTRUSTED).out(OutputStream.nullOutputStream())
                            .err(OutputStream.nullOutputStream()).option("sandbox.MaxThreads", "1")
                            .options(p.contextOptions()).build()) {
                c.eval("js", "1");
                ok = true;
                detail = "available";
            } catch (RuntimeException ex) {
                ok = false;
                detail = String.valueOf(ex.getMessage());
            }
            protectionAvailable = ok;
            bus.publish(origin.get(), "sandbox.memory_protection", dev.jharness.events.Json.object()
                    .put("available", ok).put("detail", detail));
        }
        return protectionAvailable;
    }
}
