package dev.jharness.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.jharness.events.Json;
import dev.jharness.security.Workspace;

/**
 * Search without {@code rg} (D-027): Java NIO walk plus {@code java.util.regex}. Like {@code rg} it
 * skips hidden files and folders, binary files and symlinks; unlike {@code rg} it does not read
 * {@code .gitignore}. Same result format as {@link RipgrepSearch}.
 */
public final class JavaSearch implements Search {

    private static final long MAX_FILE_BYTES = 10L << 20;

    private final Workspace ws;

    public JavaSearch(Workspace ws) {
        this.ws = ws;
    }

    @Override
    public String engine() {
        return "java";
    }

    /** rg-style glob: without a '/', it matches the file name anywhere; a leading '!' excludes. */
    static PathMatcher glob(String glob) {
        if (glob == null) {
            return p -> true;
        }
        boolean negate = glob.startsWith("!");
        String g = negate ? glob.substring(1) : glob;
        PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + g);
        boolean byName = !g.contains("/");
        PathMatcher base = p -> byName ? m.matches(p.getFileName()) : m.matches(p);
        return negate ? p -> !base.matches(p) : base;
    }

    private List<Path> walk(Path dir, String glob) {
        PathMatcher matcher = glob(glob);
        List<Path> out = new ArrayList<>();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                    return !d.equals(dir) && hidden(d) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    if (a.isRegularFile() && !hidden(f) && matcher.matches(ws.root().relativize(f))) {
                        out.add(f);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new OperationException("cannot list " + ws.relative(dir) + ": " + e.getMessage());
        }
        out.sort(null);
        return out;
    }

    private static boolean hidden(Path p) {
        return p.getFileName() != null && p.getFileName().toString().startsWith(".");
    }

    @Override
    public ObjectNode listFiles(Path dir, String glob, int max) {
        ObjectNode out = Json.object();
        ArrayNode files = out.putArray("files");
        boolean truncated = false;
        for (Path f : walk(dir, glob)) {
            if (files.size() >= max) {
                truncated = true;
                break;
            }
            files.add(ws.relative(f));
        }
        out.put("truncated", truncated);
        return out;
    }

    @Override
    public ObjectNode grep(String pattern, Path dir, String glob, boolean ignoreCase, int max) {
        Pattern p;
        try {
            p = Pattern.compile(pattern, ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
        } catch (PatternSyntaxException e) {
            throw new OperationException("bad pattern: " + e.getDescription());
        }
        ObjectNode out = Json.object();
        ArrayNode matches = out.putArray("matches");
        List<Path> files = Files.isRegularFile(dir) ? List.of(dir) : walk(dir, glob);
        for (Path f : files) {
            try {
                if (Files.size(f) > MAX_FILE_BYTES || binary(f)) {
                    continue;
                }
                var dec = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT);
                try (BufferedReader r = new BufferedReader(new java.io.InputStreamReader(Files.newInputStream(f), dec))) {
                    String line;
                    int n = 0;
                    while ((line = r.readLine()) != null) {
                        n++;
                        if (p.matcher(line).find()) {
                            if (matches.size() >= max) {
                                out.put("truncated", true);
                                return out;
                            }
                            matches.addObject().put("path", ws.relative(f)).put("line", n).put("text", Search.clip(line));
                        }
                    }
                }
            } catch (CharacterCodingException e) {
                // Not UTF-8 text: skipped, as rg skips binary files.
            } catch (IOException e) {
                // Unreadable files are skipped, as rg does with --no-messages.
            }
        }
        out.put("truncated", false);
        return out;
    }

    private static boolean binary(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            byte[] b = in.readNBytes(8192);
            for (byte x : b) {
                if (x == 0) {
                    return true;
                }
            }
            return false;
        }
    }
}
