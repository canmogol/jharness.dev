import java.util.List;
import java.util.stream.IntStream;

public class CheckPager {
    static void eq(Object want, Object got, String what) {
        if (!want.equals(got)) {
            throw new AssertionError(what + ": expected " + want + " but was " + got);
        }
    }

    public static void main(String[] args) {
        Pager p = new Pager(10);
        eq(0, p.pageCount(0), "pageCount(0)");
        eq(1, p.pageCount(1), "pageCount(1)");
        eq(1, p.pageCount(10), "pageCount(10)");
        eq(3, p.pageCount(25), "pageCount(25)");
        List<Integer> r = IntStream.range(0, 25).boxed().toList();
        eq(IntStream.range(0, 10).boxed().toList(), p.page(r, 1), "page 1");
        eq(IntStream.range(20, 25).boxed().toList(), p.page(r, 3), "page 3");
        eq(List.of(), p.page(r, 4), "page 4");
        eq(IntStream.range(10, 20).boxed().toList(), p.page(IntStream.range(0, 20).boxed().toList(), 2), "page 2 of 20");
        System.out.println("ok");
    }
}
