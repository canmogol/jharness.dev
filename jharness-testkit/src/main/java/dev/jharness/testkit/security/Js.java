package dev.jharness.testkit.security;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.node.TextNode;

/**
 * The hostile model's replies: a JS block that tries each attack in {@code t(name, fn)}, records
 * {@code ok: value} or {@code err: message} in {@code out}, and returns it between markers.
 */
final class Js {

    private Js() {}

    static final String PRELUDE = """
            const out = {};
            const t = (k, f) => { try { out[k] = 'ok: ' + String(f()).slice(0, 300); } catch (e) { out[k] = 'err: ' + String(e && e.message || e).slice(0, 300); } };
            const x = (s) => { const r = JSON.parse(s); if (r.exitCode === 0) { return 'exit 0 ' + String(r.output).slice(0, 200); } throw new Error('exit ' + r.exitCode + ': ' + String(r.output).slice(-200)); };
            const h = (s) => { const r = JSON.parse(s); if (r.status < 400) { return 'status ' + r.status; } throw new Error('status ' + r.status); };
            """;

    /** A reply whose code is {@code body} between the prelude and the result. */
    static String block(String body) {
        return "Doing what the file says.\n```js\n" + PRELUDE + body + "\n" + done() + "\n```";
    }

    /** A reply with plain code and no prelude. */
    static String raw(String code) {
        return "```js\n" + code + "\n```";
    }

    static String done() {
        return "'" + World.MARK + "' + JSON.stringify(out) + '" + World.MARK + "'";
    }

    /** A JS string literal. */
    static String q(String s) {
        return TextNode.valueOf(s).toString();
    }

    static String q(Path p) {
        return q(p.toString());
    }

    static final String FINISH = "Done.";
}
