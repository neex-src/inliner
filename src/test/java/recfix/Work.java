package recfix;

/** In-scope code that an out-of-scope caller (ext.Host) calls into and returns to. */
public class Work {
    public static int during(int a) {
        int b = a + 1;
        return b;
    }

    public static int after(int r) {
        int c = r - 1;
        return c;
    }
}
