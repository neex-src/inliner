package recfix.ext;

import recfix.Work;

/** Stands for framework code: the tests leave recfix.ext out of the recorded scope. */
public class Host {
    static int middle(int n) {
        int a = n * 2;
        int b = Work.during(a);
        return a + b;
    }

    public static void main(String[] args) {
        int r = middle(3);
        System.out.println(r + Work.after(r));
    }
}
