package demo;
import java.util.List;

@Deprecated
public class Order {
    // class Fake { void pretend() {} }
    private String label = "if (fake) call()";
    public int total(int amount) {
        if (amount < 500) {
            throw new IllegalArgumentException("Minimum");
        }
        for (int i = 0; i < 3; i++) amount += i;
        return Math.max(amount, 500);
    }
}
