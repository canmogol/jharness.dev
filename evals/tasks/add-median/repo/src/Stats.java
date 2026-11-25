/** Summary statistics for the latency dashboard. */
public final class Stats {

    private Stats() {}

    /** The arithmetic mean; throws for an empty array. */
    public static double mean(int[] values) {
        if (values.length == 0) {
            throw new IllegalArgumentException("no values");
        }
        long sum = 0;
        for (int v : values) {
            sum += v;
        }
        return (double) sum / values.length;
    }
}
