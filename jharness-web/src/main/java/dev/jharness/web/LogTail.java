package dev.jharness.web;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Follows a session log for {@code jharness view} (spec 048): every line already there, then each line appended,
 * checked every {@link #POLL_MS}. A line is added only once it is complete.
 */
public final class LogTail implements AutoCloseable {

    static final long POLL_MS = 250;

    private final Path file;
    private final EventFeed feed;
    private final Thread thread;
    private volatile boolean closed;

    private LogTail(Path file, EventFeed feed) {
        this.file = file;
        this.feed = feed;
        this.thread = new Thread(this::run, "jharness-view-tail");
        this.thread.setDaemon(true);
    }

    public static LogTail start(Path file, EventFeed feed) throws IOException {
        if (!file.toFile().isFile()) {
            throw new IOException(file + " is not a session log");
        }
        LogTail t = new LogTail(file, feed);
        t.readAvailable(0);
        t.thread.start();
        return t;
    }

    private long position;
    private final java.io.ByteArrayOutputStream partial = new java.io.ByteArrayOutputStream();

    /** Reads what was appended since {@code from}; returns the new position. */
    synchronized long readAvailable(long from) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            long len = f.length();
            if (len < position) {
                // The file was replaced or cut; start again.
                position = 0;
                partial.reset();
            }
            f.seek(position);
            byte[] buf = new byte[64 * 1024];
            while (position < len) {
                int n = f.read(buf, 0, (int) Math.min(buf.length, len - position));
                if (n <= 0) {
                    break;
                }
                position += n;
                for (int i = 0; i < n; i++) {
                    if (buf[i] == '\n') {
                        String line = partial.toString(StandardCharsets.UTF_8).strip();
                        partial.reset();
                        if (!line.isEmpty()) {
                            feed.add(line);
                        }
                    } else {
                        partial.write(buf[i]);
                    }
                }
            }
        }
        return position;
    }

    private void run() {
        while (!closed) {
            try {
                Thread.sleep(POLL_MS);
                readAvailable(position);
            } catch (InterruptedException e) {
                return;
            } catch (IOException e) {
                // The log may be gone; keep what was shown and try again.
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        thread.interrupt();
    }
}
