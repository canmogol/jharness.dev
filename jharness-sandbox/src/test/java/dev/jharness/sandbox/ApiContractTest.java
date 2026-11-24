package dev.jharness.sandbox;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.graalvm.polyglot.HostAccess;
import org.junit.jupiter.api.Test;

/** api.d.ts, the JS shim and HostApi must declare the same methods with the same arity (AC-05). */
class ApiContractTest {

    private static Map<String, Integer> declared() {
        String d = AgentIsolate.apiDeclaration();
        String body = d.substring(d.indexOf("declare const api"));
        Map<String, Integer> m = new TreeMap<>();
        Matcher x = Pattern.compile("^\\s+(\\w+)\\(([^)]*)\\):", Pattern.MULTILINE).matcher(body);
        while (x.find()) {
            String params = x.group(2).trim();
            m.put(x.group(1), params.isEmpty() ? 0 : params.split(",").length);
        }
        return m;
    }

    private static Map<String, Integer> host() {
        Map<String, Integer> m = new TreeMap<>();
        for (Method method : HostApi.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(HostAccess.Export.class)) {
                m.put(method.getName(), method.getParameterCount());
            }
        }
        return m;
    }

    private static Map<String, Integer> shim() {
        Map<String, Integer> m = new TreeMap<>();
        Matcher x = Pattern.compile("^\\s+(\\w+): \\(([^)]*)\\) =>", Pattern.MULTILINE).matcher(AgentIsolate.shim());
        while (x.find()) {
            String params = x.group(2).trim();
            m.put(x.group(1), params.isEmpty() ? 0 : params.split(",").length);
        }
        return m;
    }

    @Test
    void declarationShimAndHostAgree() {
        Map<String, Integer> host = host();
        assertEquals(19, host.size(), host.toString());
        assertEquals(host, declared(), "api.d.ts differs from HostApi");
        assertEquals(host, shim(), "the JS shim differs from HostApi");
    }

    @Test
    void nativeMetadataListsEveryHostMethod() throws Exception {
        String meta;
        try (var in = HostApi.class.getResourceAsStream("/META-INF/native-image/dev.jharness/jharness-sandbox/reachability-metadata.json")) {
            meta = new String(in.readAllBytes());
        }
        for (String name : host().keySet()) {
            assertTrue(meta.contains("\"name\": \"" + name + "\""), name + " missing from reachability metadata");
        }
    }
}
