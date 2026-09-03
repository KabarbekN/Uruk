package example.orders;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.context.ApplicationEventPublisher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderServiceTest {
    private final OrderRepository repository = mock(OrderRepository.class);
    private final PaymentClient payments = mock(PaymentClient.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final OrderService service = new OrderService(repository, payments, events);

    @Test
    @DisplayName("Reject amounts below the minimum")
    void rejectsBelowMinimum() {
        assertThrows(OrderRejectedException.class,
                () -> service.createOrder(new OrderRequest(299, "alice", false)));
    }

    @Test
    @DisplayName("Premium customers receive a ten percent discount")
    void premiumDiscount() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Order order = service.createOrder(new OrderRequest(300, "alice", true));
        assertEquals(270, order.getAmount());
    }
}
