package dev.jharness.agent;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the code to run in a model reply: the first fenced block whose info string is {@code js} or
 * {@code javascript}, with three or more backticks or tildes. Other blocks ({@code json}, {@code ts},
 * unlabelled) are ignored. {@link #find} also counts the later {@code js} blocks, which don't run.
 */
public final class JsBlocks {

    private static final Pattern OPEN = Pattern.compile("(?m)^[ \\t]{0,3}(`{3,}|~{3,})[ \\t]*([^\\s`]*)[^\\n]*$");

    private JsBlocks() {}

    /** The code to run and how many later {@code js} blocks the reply had. */
    public record Found(String code, int ignored) {}

    public static Optional<String> first(String reply) {
        return find(reply).map(Found::code);
    }

    public static Optional<Found> find(String reply) {
        if (reply == null) {
            return Optional.empty();
        }
        String code = null;
        int ignored = 0;
        Matcher m = OPEN.matcher(reply);
        int from = 0;
        while (from < reply.length() && m.find(from)) {
            String fence = m.group(1);
            String info = m.group(2).toLowerCase();
            int bodyStart = m.end() + 1;
            if (bodyStart > reply.length()) {
                break;
            }
            Pattern close = Pattern.compile("(?m)^[ \\t]{0,3}" + Pattern.quote(String.valueOf(fence.charAt(0))) + "{" + fence.length() + ",}[ \\t]*$");
            Matcher c = close.matcher(reply);
            int bodyEnd;
            int next;
            if (c.find(bodyStart)) {
                bodyEnd = c.start();
                next = c.end();
            } else {
                // An unclosed block runs to the end of the reply (the stream may have stopped at max tokens).
                bodyEnd = reply.length();
                next = reply.length();
            }
            if (info.equals("js") || info.equals("javascript")) {
                if (code == null) {
                    String body = reply.substring(bodyStart, Math.max(bodyStart, bodyEnd));
                    code = body.endsWith("\n") ? body.substring(0, body.length() - 1) : body;
                } else {
                    ignored++;
                }
            }
            from = next;
        }
        return code == null ? Optional.empty() : Optional.of(new Found(code, ignored));
    }
}
