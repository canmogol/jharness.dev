import java.util.List;

/** Splits search results into pages of a fixed size. Pages are numbered from 1. */
public final class Pager {

    private final int size;

    public Pager(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("page size must be at least 1");
        }
        this.size = size;
    }

    /** How many pages {@code total} results fill. */
    public int pageCount(int total) {
        return total / size;
    }

    /** The results on page {@code page}; an empty list past the last page. */
    public <T> List<T> page(List<T> results, int page) {
        int from = (page - 1) * size;
        if (from >= results.size()) {
            return List.of();
        }
        int to = Math.min(from + size, results.size() - 1);
        return results.subList(from, to);
    }
}
