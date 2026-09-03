package example.orders;

public class OrderRejectedException extends RuntimeException {
    public OrderRejectedException(String message) {
        super(message);
    }
}
