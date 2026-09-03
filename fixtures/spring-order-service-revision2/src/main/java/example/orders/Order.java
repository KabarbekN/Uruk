package example.orders;

import java.time.Instant;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Column;

@Entity
@Table(name = "orders", schema = "public")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "created_at", insertable = false, updatable = false, columnDefinition = "timestamptz DEFAULT now()")
    private Instant createdAt;

    @Column(name = "status", insertable = false, columnDefinition = "varchar(32) DEFAULT 'PENDING'")
    private String status;

    protected Order() {}

    public Order(String customerId, long amount) {
        this.customerId = customerId;
        this.amount = amount;
    }

    public Long getId() { return id; }
    public long getAmount() { return amount; }
}
