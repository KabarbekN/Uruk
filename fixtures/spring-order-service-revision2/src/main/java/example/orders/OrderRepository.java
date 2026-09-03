package example.orders;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByStatus(String status);

    @Query("select o from Order o where o.customerId = :customerId")
    List<Order> findForCustomer(@Param("customerId") String customerId);
}
