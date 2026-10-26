package recfix;

/** Call shapes the call tree must tell apart. */
public class Calls {
    static int fact(int n) {
        if (n <= 1) {
            return 1;
        }
        return n * fact(n - 1);
    }

    static int twice(int x) {
        int y = x * 2;
        return y;
    }

    /** Starts with a loop: the loop jumps back to the first line, which is not a new call. */
    static int countDown(int n) {
        while (n > 0) {
            n--;
        }
        return n;
    }

    static void fail(int depth) {
        if (depth == 0) {
            throw new IllegalStateException("boom");
        }
        fail(depth - 1);
    }

    static int rescue() {
        int got = 0;
        try {
            fail(2);
        } catch (IllegalStateException e) {
            got = -1;
        }
        return got;
    }

    /** A tableswitch: the return finder has to step over its padding and jump table. */
    static String kind(int n) {
        switch (n) {
            case 1: return "one";
            case 2: return "two";
            case 3: return "three";
            default: return "many";
        }
    }

    /** A lookupswitch. */
    static int sparse(int n) {
        switch (n) {
            case 10: return 1;
            case 1000: return 2;
            default: return 0;
        }
    }

    public static void main(String[] args) {
        int f = fact(3);
        int a = twice(1); int b = twice(2);
        int c = countDown(3);
        int r = rescue();
        String k = kind(2);
        int s = sparse(1000);
        System.out.println(f + a + b + c + r + k + s);
    }
}
