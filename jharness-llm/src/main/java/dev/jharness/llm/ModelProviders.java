package dev.jharness.llm;

import java.util.function.Function;
import java.util.function.Supplier;

import dev.jharness.config.ModelProfile;
import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;
import dev.jharness.net.http.HttpClients;

/** Builds the provider for a model profile (D-032). */
public final class ModelProviders {

    private ModelProviders() {}

    public static ModelProvider create(ModelProfile p, EventBus bus, Supplier<Origin> origin) {
        return create(p, bus, origin, System::getenv);
    }

    public static ModelProvider create(ModelProfile p, EventBus bus, Supplier<Origin> origin, Function<String, String> env) {
        return create(p, bus, origin, env, null);
    }

    /** @param waiter waits before a retry (spec 020); null for the real wait, which Esc ends */
    public static ModelProvider create(ModelProfile p, EventBus bus, Supplier<Origin> origin, Function<String, String> env,
            RetryWait waiter) {
        return switch (p.provider()) {
            case ModelProfile.OPENAI_COMPATIBLE -> new OpenAiCompatibleProvider(p, HttpClients.create(), bus, origin, env, waiter);
            case ModelProfile.ANTHROPIC -> new AnthropicProvider(p, HttpClients.create(), bus, origin, env, waiter);
            default -> throw new IllegalArgumentException("unknown provider " + p.provider());
        };
    }
}
