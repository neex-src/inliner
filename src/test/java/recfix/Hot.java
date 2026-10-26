package recfix;

/** A hot loop for the budget, and two threads. */
public class Hot {
    static long spin(int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += i;
        }
        return sum;
    }

    static int work(String who) {
        int total = 0;
        for (int i = 0; i < 3; i++) {
            total += who.length();
        }
        return total;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("threads")) {
            Thread t1 = new Thread(() -> work("one"), "worker-1");
            Thread t2 = new Thread(() -> work("three"), "worker-2");
            t1.start();
            t1.join();
            t2.start();
            t2.join();
        } else {
            System.out.println(spin(1000));
        }
    }
}
