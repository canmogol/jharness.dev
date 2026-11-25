import java.util.Arrays;

public class CheckStats {
    static void eq(double want, double got, String what) {
        if (Math.abs(want - got) > 1e-9) {
            throw new AssertionError(what + ": expected " + want + " but was " + got);
        }
    }

    public static void main(String[] args) {
        eq(3, Stats.median(new int[] {5, 1, 3}), "odd count, unsorted");
        eq(2.5, Stats.median(new int[] {4, 1, 3, 2}), "even count");
        eq(7, Stats.median(new int[] {7}), "one value");
        int[] input = {9, 2, 7};
        Stats.median(input);
        if (!Arrays.equals(new int[] {9, 2, 7}, input)) {
            throw new AssertionError("median changed its input: " + Arrays.toString(input));
        }
        try {
            Stats.median(new int[0]);
            throw new AssertionError("an empty array must throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        eq(2, Stats.mean(new int[] {1, 3}), "mean still works");
        System.out.println("ok");
    }
}
