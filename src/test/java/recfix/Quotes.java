package recfix;

import java.util.ArrayList;
import java.util.List;

/** A small priced order: a loop, a branchy policy, a stream lambda, records, a getter. */
public class Quotes {
    record Customer(String name, int yearsLoyal, boolean business) {}

    record Line(String sku, int qty, long unit) {
        long amount() {
            return qty * unit;
        }
    }

    private String currency = "EUR";

    public String getCurrency() {
        return currency;
    }

    long discount(Customer c, long subtotal, int items) {
        boolean loyal = c.yearsLoyal() >= 3;
        boolean bulk = items >= 3;
        int percent = 0;
        if (loyal) {
            percent += 5;
        }
        if (bulk) {
            percent += c.business() ? 8 : 4;
        }
        return subtotal * percent / 100;
    }

    long quote(Customer c, int items) {
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < items; i++) {
            lines.add(new Line("SKU-" + i, 1 + i, 100));
        }
        long subtotal = lines.stream().mapToLong(l -> l.amount()).sum();
        long off = discount(c, subtotal, items);
        return subtotal - off;
    }

    public static void main(String[] args) {
        Quotes q = new Quotes();
        long total = q.quote(new Customer("Ana", 4, false), 3);
        System.out.println(q.getCurrency() + " " + total);
    }
}
