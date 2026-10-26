package recfix;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shapes the renderer must show without running anything in the debuggee. */
public class Values {
    enum Color { RED, GREEN }

    static class Box {
        int width = 3;
        String label = "crate";
        Box inner;
    }

    static void look(String s, Integer boxed, Color color, int[] numbers, Box box, List<String> list, Map<String, Integer> map, Object nothing, char ch) {
        int marker = 1;
    }

    public static void main(String[] args) {
        Box box = new Box();
        sandbox.inner = new Box();
        List<String> list = new ArrayList<>(List.of("a", "b"));
        Map<String, Integer> map = new HashMap<>(Map.of("k", 1));
        look("line\nbreak", 42, Color.GREEN, new int[]{1, 2, 3}, box, list, map, null, 'x');
    }
}
