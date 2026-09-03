package example.orders;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "payments", url = "${payments.url}")
public interface PaymentClient {
    @PostMapping("/charge")
    void charge(@RequestParam("amount") long amount);
}
