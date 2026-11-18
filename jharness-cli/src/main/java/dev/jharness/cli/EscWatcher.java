package dev.jharness.cli;

import java.io.IOException;

import org.jline.utils.NonBlockingReader;

/**
 * Watches the terminal for Esc while a turn runs (US-2.5). Paused while an approval question reads
 * the terminal, so the two never compete for keystrokes.
 */
final class EscWatcher implements AutoCloseable {

    private final NonBlockingReader reader;
    private final Runnable onEsc;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile boolean paused;
    private volatile boolean idle;

    EscWatcher(NonBlockingReader reader, Runnable onEsc) {
        this.reader = reader;
        this.onEsc = onEsc;
        this.thread = Thread.ofPlatform().daemon().name("esc-watcher").start(this::loop);
    }

    private void loop() {
        while (running) {
            if (paused) {
                idle = true;
                sleep();
                continue;
            }
            idle = false;
            try {
                int c = reader.read(100);
                if (c == 27 && running && !paused) {
                    onEsc.run();
                } else if (c == NonBlockingReader.EOF) {
                    return;
                }
            } catch (IOException e) {
                return;
            }
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops reading and waits until the watcher is not inside a read. */
    void pause() {
        paused = true;
        long end = System.nanoTime() + 500_000_000L;
        while (!idle && thread.isAlive() && System.nanoTime() < end) {
            sleep();
        }
    }

    void resume() {
        paused = false;
    }

    @Override
    public void close() {
        running = false;
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
