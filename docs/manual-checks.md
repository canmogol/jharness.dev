# Manual checks: browsers and terminal sizes

Spec 053, D-086. Two scripts check what CI can't see cheaply: the web viewer in real browsers, and the TUI at real terminal sizes in tmux. They never run in CI (browsers add minutes to every run). Run them by hand:

- after any change to the web viewer (`jharness-web`: page, scripts, styles, server);
- after any change to the terminal UI (`Tui`, `StatusLine`, rendering);
- before each release, on Linux and on macOS (a release check, like the live model run).

Both take a native binary and write what they saw under `target/`.

## Viewer in browsers

`scripts/viewer-check.mjs` records a session whose reply has Markdown (bold, a code block, a list, a table, a link), starts `jharness view` on it and opens the address in each browser. After the page says "live" it checks:

| Check | Fails on |
| --- | --- |
| The page loaded | `app.css` not applied, `app.js` or `markdown.js` not loaded |
| Clean console | A console error, a page error or a CSP violation |
| No refused requests | Any response of 400 or more, the favicon included; any failed request |
| Markdown | No `<strong>`, `<pre>`, list items, `<table>`, or `<a>` with an `https` address; raw `**` or fences left |
| Terminal | `jharness view` didn't print `session.started`, `turn.started`, `model.completed` and `turn.completed` |

It saves a full-page screenshot per browser (`target/viewer-check/viewer-BROWSER-OS.png`) and exits 1 if any check fails.

### Setup

Playwright is not part of the build or CI; install it once per machine:

```bash
npm install -g playwright
npx playwright install chromium firefox webkit
sudo "$(command -v npx)" playwright install-deps   # Linux: the browsers' system libraries
```

Without Playwright the script says how to install it and exits 2.

For the real Safari (macOS): Safari > Settings > Advanced > "Show features for web developers", then Develop > "Allow Remote Automation", and `safaridriver --enable` once. WebDriver has no console or network log, so for Safari it checks the page, the Markdown and CSP violations the page saw after loading.

### Run

```bash
node scripts/viewer-check.mjs jharness-cli/target/jharness                                  # Linux: chromium, firefox, webkit
node scripts/viewer-check.mjs jharness-cli/target/jharness --browsers webkit,chromium,safari # macOS
```

## Terminal sizes

`scripts/tui-tmux-check.py` complements `scripts/tui-pty-check.py` (which CI runs). For each size (default `80x24,120x40,50x16`) it starts the TUI in a detached tmux session with a recorded Markdown reply, then:

1. answers the trust question and sends a prompt;
2. checks the reply's styles in `capture-pane -e`: bold, a coloured code line, no raw `**`;
3. checks that the status line is the last row and appears once;
4. resizes the window larger, then smaller, and checks the status line again;
5. sends Ctrl-D and checks that JHarness exited 0.

Each screen is saved with its escape codes (`target/tui-check/tui-WxH-*.ansi`; `cat` one to see it) and as text. It needs `tmux` (`apt install tmux`, `brew install tmux`).

```bash
scripts/tui-tmux-check.py jharness-cli/target/jharness
scripts/tui-tmux-check.py jharness-cli/target/jharness --sizes 100x30,60x20
```

## Results

Record notable runs here: date, version, machine, result.

| Date | Version | Machine | Viewer | TUI sizes |
| --- | --- | --- | --- | --- |
| 2026-11-25 | 0.1.0-SNAPSHOT (spec 053) | macOS 14.3 arm64 | Chromium 159 and Firefox 159 pass; Playwright's WebKit for macOS 14 crashes on start (Bus error), use Safari; Safari needs Allow Remote Automation | 120x40 passes; shrinking below the prompt's row puts the prompt on the status line (80x24 → 60x18, 50x16 → 40x12) |
| 2026-11-25 | 0.1.0-SNAPSHOT (spec 053) | Linux x86-64 | Browsers need their system libraries (`playwright install-deps`, sudo); the terminal check passes | Same resize finding as macOS |
| 2026-11-25 | 0.1.0-SNAPSHOT (spec 054) | macOS 14.3 arm64 | Not run: the viewer didn't change | 120x40 passes; the resize finding is unchanged |
| 2026-11-25 | 0.1.0-SNAPSHOT (spec 054) | Linux x86-64 | Not run: the viewer didn't change | Same as macOS |
| 2026-11-26 | 0.1.0-SNAPSHOT (spec 055) | macOS 14.3 arm64 | Not run: the viewer didn't change | 120x40 passes; the resize finding is unchanged. Typing during a turn, the queue, Esc, `/answer main`, a waiting command and `/exit` during a turn pass in tmux |
| 2026-11-26 | 0.1.0-SNAPSHOT (spec 055) | Linux x86-64 | Not run: the viewer didn't change | Same as macOS |

Notes:

- A macOS native binary built where `LC_CTYPE` is `C` (such as an SSH session with `LANG=C.UTF-8`, which macOS doesn't define) prints `?` for non-ASCII characters such as `·`, whatever the runtime locale. Build with a UTF-8 locale (`LC_ALL=en_US.UTF-8`).

