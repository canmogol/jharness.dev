package dev.jharness.testkit;

/**
 * Model replies in each provider's streaming wire format (OpenAI Chat Completions chunks, Anthropic
 * Messages events), for the stub server. Live tests check the same parsing against real servers.
 */
public final class Streams {

    private Streams() {}

    public static final String OPENAI_OK = """
            data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

            data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"```js\\n"},"finish_reason":null}]}

            data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"api.readFile('a')\\n```"},"finish_reason":null}]}

            data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: {"id":"c1","object":"chat.completion.chunk","choices":[],"usage":{"prompt_tokens":12,"completion_tokens":9,"total_tokens":21}}

            data: [DONE]

            """;

    /** Ends mid-reply: no finish_reason and no [DONE]. */
    public static final String OPENAI_CUT = """
            data: {"id":"c2","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"partial"},"finish_reason":null}]}

            """;

    public static final String OPENAI_ERROR = "{\"error\":{\"message\":\"model 'nope' not found\",\"type\":\"not_found\"}}";

    public static final String ANTHROPIC_OK = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],"model":"claude-test","stop_reason":null,"usage":{"input_tokens":25,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: ping
            data: {"type":"ping"}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":", world"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":4}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    /** An error event in the middle of a stream. */
    public static final String ANTHROPIC_STREAM_ERROR = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_2","usage":{"input_tokens":5}}}

            event: error
            data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

            """;

    /** Ends without message_stop. */
    public static final String ANTHROPIC_CUT = """
            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}

            """;

    /** Text first, then an overloaded error: the user already saw text, so it isn't sent again. */
    public static final String ANTHROPIC_OVERLOADED_AFTER_TEXT = """
            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}

            event: error
            data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

            """;

    public static final String ANTHROPIC_RATE_LIMIT =
            "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"Number of requests has exceeded your rate limit\"}}";

    public static final String ANTHROPIC_OVERLOADED =
            "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}";

    public static final String ANTHROPIC_401 =
            "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}";

    /** Thinking in {@code reasoning_content} (LM Studio, llama.cpp) and {@code reasoning} (Ollama), then the reply. */
    public static final String OPENAI_REASONING = """
            data: {"choices":[{"index":0,"delta":{"role":"assistant","content":"","reasoning_content":"The user says hi."},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"reasoning":" Greet back."},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"content":"\\n\\n"},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"content":"Hi!"},"finish_reason":"stop"}]}

            data: [DONE]

            """;

    /** Inline thinking with split tags and a code block inside it, which must not run. */
    public static final String OPENAI_THINK_TAGS = """
            data: {"choices":[{"index":0,"delta":{"content":"<thi"},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"content":"nk>Maybe:\\n```js\\napi.writeFile('x', 'y')\\n```\\n</th"},"finish_reason":null}]}

            data: {"choices":[{"index":0,"delta":{"content":"ink>\\n\\nNo code needed."},"finish_reason":"stop"}]}

            data: [DONE]

            """;

    public static final String ANTHROPIC_THINKING = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_2","type":"message","role":"assistant","content":[],"model":"claude-test","stop_reason":null,"usage":{"input_tokens":5,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Check the file."}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Done."}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":9}}

            event: message_stop
            data: {"type":"message_stop"}

            """;
}
