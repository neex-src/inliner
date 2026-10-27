package recfix;

/** A branch decided by a flag computed upstream: what if the flag were the other way? */
public class Flags {
    static int percent(int items) {
        boolean bulk = items >= 3;
        int percent = 0;
        if (bulk) {
            percent = 5;
        }
        return percent;
    }

    public static void main(String[] args) {
        System.out.println(percent(4));
    }
}
