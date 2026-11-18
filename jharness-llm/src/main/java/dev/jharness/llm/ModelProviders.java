package dev.jharness.llm;

import java.util.function.Function;
import java.util.function.Supplier;

import dev.jharness.config.ModelProfile;
import dev.jharness.events.EventBus;
import dev.jharness.events.Origin;
import dev.jharness.llm.http.HttpClients;

/** Builds the provider for a model profile (D-032). */
public final class ModelProviders {

    private ModelProviders() {}

    public static ModelProvider create(ModelProfile p, EventBus bus, Supplier<Origin> origin) {
        return create(p, bus, origin, System::getenv);
    }

    public static ModelProvider create(ModelProfile p, EventBus bus, Supplier<Origin> origin, Function<String, String> env) {
        return switch (p.provider()) {
            case ModelProfile.OPENAI_COMPATIBLE -> new OpenAiCompatibleProvider(p, HttpClients.create(), bus, origin, env);
            case ModelProfile.ANTHROPIC -> new AnthropicProvider(p, HttpClients.create(), bus, origin, env);
            default -> throw new IllegalArgumentException("unknown provider " + p.provider());
        };
    }
}
