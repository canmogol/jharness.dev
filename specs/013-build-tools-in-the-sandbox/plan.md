# Plan 013 — Maven, Gradle and npm in the sandbox

Spec: `spec.md` · Status: Draft · Created: 2026-11-19

## Approach

`BuildOperations` in `jharness-process` turns typed requests into argv lists for Maven, Gradle and npm. It runs them through `ProcessRunner` with `Grants`: extra writable cache folders, plus the network flag. `ChildSandbox` learns the proxy route (port, Unix socket and credentials) from `Session`, and opens only that route for runs with network. On macOS, that is one Seatbelt rule. On Linux, the JHarness binary runs again inside bubblewrap as `ProxyBridge`: it listens on `127.0.0.1:<port>` in the private network namespace, forwards each connection to a Unix socket that `EgressProxy` serves, and starts the tool as its child. Decision D-049.

## Constitution check

| Article | Answer | Pass |
| --- | --- | --- |
| I One binary | The bridge is the same binary in another mode (`__proxy-bridge`); no new executable is shipped | Yes |
| II Host owns side effects | Agent code sends typed requests; the host builds argv, settings files and sandbox rules | Yes |
| III Untrusted | No change to the isolate; three new `api` methods with JSON strings | Yes |
| IV No shell | argv lists only; wrappers only in trusted projects (the constitution's exception); `ProcessBuilder` stays in `ProcessRunner` and in the bridge, which runs inside the sandbox and starts the argv `ProcessRunner` gave it | Yes |
| V Proxy | Every tool is pointed at the proxy, and the sandbox blocks every other connection; the bridge only connects to the proxy's socket | Yes |
| VI Strings | Requests and results are JSON strings | Yes |
| VII Safe by default | Builds need trust and approval; no network without the proxy; the password is redacted from output | Yes |
| VIII Events | `api.call`, `permission.*`, `process.started` (with `network`), `process.completed`, `network.*` | Yes |
| IX Native first | No new dependency; Unix domain sockets are in the JDK; the native smoke binary runs the bridge | Yes |
| X Tested | Request validation, sandbox rules, proxied and direct connections, real tools when installed | Yes |
| XI Spec first | Every task traces to an AC | Yes |

## Design

```java
// jharness-process
public final class ChildSandbox {
    public record ProxyRoute(int port, Path socket, String user, String password) {}
    public record Grants(List<Path> writable, boolean network) { public static final Grants NONE; }
    public ChildSandbox withProxy(ProxyRoute route, List<String> bridgeCommand);
    public static List<String> selfBridgeCommand();     // native: [<binary>, "__proxy-bridge"]; JVM: [java, -cp, cp, ProxyBridge]
    List<String> wrap(List<String> argv, Path cwd, Grants g);
}
public final class ProxyBridge { public static void main(String[] a); }  // <socket> <port> -- argv...
public final class BuildOperations {
    public enum Tool { MAVEN, GRADLE, NPM }
    public BuildOperations(ProcessRunner r, Workspace ws, Timeouts t, boolean trusted, Map<Tool, Executable> onPath, Path home);
    public static Operation.Kind kind();                // BUILD
    public ProcessResult run(Tool tool, ObjectNode req);
}
// jharness-net
EgressProxy.serveUnix(Path socket);                     // relays each Unix connection to the TCP listener
```

### Network per platform

| Platform | Rule |
| --- | --- |
| macOS | `(allow network-outbound (remote ip "localhost:<port>"))` only when `Grants.network` |
| Linux | `--bind <socket> <socket>`, argv becomes `<bridge> <socket> <port> -- <tool argv>`; the namespace's loopback is private |

### Pointing tools at the proxy

| Tool | How |
| --- | --- |
| Maven | `--global-settings <session>/net/maven-settings.xml` (0600) with `http` and `https` proxy entries |
| Gradle, and JVMs Maven forks | `JAVA_TOOL_OPTIONS`: `http(s).proxyHost/Port/User/Password`, `jdk.http.auth.tunneling.disabledSchemes=`, `java.io.tmpdir` |
| npm | `npm_config_proxy`, `npm_config_https_proxy`, `npm_config_update_notifier=false`, `npm_config_fund=false`, `npm_config_audit=false` |

`JAVA_TOOL_OPTIONS` is echoed by the JVM ("Picked up …"), so `ProcessRunner` replaces the proxy password with `***` in all captured output. `PATH` for a build tool adds the folder it was found in and `$JAVA_HOME/bin`.

### Requests

| Tool | Ops | Fields | argv |
| --- | --- | --- | --- |
| maven | compile, test, verify, package, clean | `module` (folder: `-pl <m> -am`), `tests` (`-Dtest=<p> -Dsurefire.failIfNoSpecifiedTests=false`) | `-B -ntp -Dstyle.color=never --global-settings <f> <op>` |
| gradle | build, test, check, assemble, clean | `project` (`:a:b` prefixes the task), `tests` (`--tests <p>`) | `--console=plain --no-daemon <task>` |
| npm | ci, install, test, run | `script` (run only; must be in `package.json`) | `ci --ignore-scripts`, `install --ignore-scripts`, `test`, `run <script>` |

Every op also takes `timeoutMs`. `tests` matches `[A-Za-z0-9_.*#,+$-]{1,200}` and can't start with `-`.

## Checks

- [ ] `mvn -pl jharness-process -am test`
- [ ] `mvn verify` on macOS; CI on Linux with bubblewrap
- [ ] Native smoke binary on macOS and in CI

## Risks

| Risk | Response |
| --- | --- |
| Tests that start local servers fail on macOS (Seatbelt can't tell them apart from other local services) | Out of scope for 013; listed in the spec; Linux works because loopback is private |
| A tool ignores proxy settings | Its connections fail in the sandbox; the error names the host |
| Gradle starts a single-use daemon JVM | `JAVA_TOOL_OPTIONS` reaches it too; the tree kill covers it |
| Unix domain sockets in Native Image | Checked by the native smoke binary on all targets |
