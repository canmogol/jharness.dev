package dev.jharness.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * A plain text table (spec 045): a header row, then rows; each column as wide as its longest value, two spaces
 * between columns, and the last column not padded. Values are escaped with {@link TerminalSafe} first, so widths
 * count what is printed.
 */
final class TextTable {

    private final List<String[]> rows = new ArrayList<>();
    private final int columns;

    TextTable(String... header) {
        columns = header.length;
        rows.add(escape(header));
    }

    TextTable row(String... values) {
        if (values.length != columns) {
            throw new IllegalArgumentException("expected " + columns + " values, got " + values.length);
        }
        rows.add(escape(values));
        return this;
    }

    private static String[] escape(String[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = TerminalSafe.escape(values[i] == null ? "" : values[i]);
        }
        return out;
    }

    /** The lines, header first, without trailing spaces. */
    List<String> lines() {
        int[] width = new int[columns];
        for (String[] r : rows) {
            for (int i = 0; i < columns; i++) {
                width[i] = Math.max(width[i], r[i].codePointCount(0, r[i].length()));
            }
        }
        List<String> out = new ArrayList<>();
        for (String[] r : rows) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < columns; i++) {
                b.append(r[i]);
                if (i < columns - 1) {
                    b.append(" ".repeat(width[i] - r[i].codePointCount(0, r[i].length()) + 2));
                }
            }
            out.add(b.toString().stripTrailing());
        }
        return out;
    }
}
