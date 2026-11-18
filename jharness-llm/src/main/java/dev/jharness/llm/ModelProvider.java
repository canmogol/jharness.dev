package dev.jharness.llm;

import java.util.function.Consumer;

/** A streaming chat model (D-013). */
public interface ModelProvider {

    /** {@code openai-compatible} or {@code anthropic}. */
    String provider();

    String model();

    /**
     * Streams a reply; each text delta goes to {@code onDelta} and is published as {@code model.delta}.
     *
     * @return the whole reply
     */
    ChatReply stream(ChatRequest request, Consumer<String> onDelta) throws ModelException;

    /** Stops a running {@link #stream} from another thread; it then throws {@link ModelException}. */
    default void cancel() {
    }
}
