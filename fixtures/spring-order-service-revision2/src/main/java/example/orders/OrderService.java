package example.orders;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {
    static final long MINIMUM_AMOUNT = 300;
    private final OrderRepository repository;
    private final PaymentClient paymentClient;
    private final ApplicationEventPublisher events;

    public OrderService(OrderRepository repository, PaymentClient paymentClient, ApplicationEventPublisher events) {
        this.repository = repository;
        this.paymentClient = paymentClient;
        this.events = events;
    }

    @Transactional
    public Order createOrder(OrderRequest request) {
        long total = request.amount();
        if (total < MINIMUM_AMOUNT) {
            throw new OrderRejectedException("Below minimum amount");
        }
        if (request.premium() && total >= MINIMUM_AMOUNT) {
            total = total * 9 / 10;
        }
        Order order = new Order(request.customerId(), total);
        Order saved = repository.save(order);
        paymentClient.charge(saved.getAmount());
        events.publishEvent(new OrderCreated(saved.getId(), saved.getAmount()));
        return saved;
    }

    public java.util.List<Order> pendingOrders() {
        return repository.findByStatus("PENDING");
    }
}
