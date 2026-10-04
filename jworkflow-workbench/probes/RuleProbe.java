/** Tiny shared pure-rule experiment, not a proposed workflow rule or product limit. */
public class RuleProbe {
    static int validateBound(int value) {return value < 0 ? 1 : value > 100 ? 2 : 0;}
    public static void main(String[] args) {
        for (int value : new int[]{Integer.MIN_VALUE, -1, 0, 100, 101, Integer.MAX_VALUE})
            System.out.println(value + ":" + validateBound(value));
    }
}
