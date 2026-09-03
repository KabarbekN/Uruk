package example.orders;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component("ownership")
public class Ownership {
    public boolean canCreate(Authentication authentication, String customerId) {
        return authentication != null && authentication.getName().equals(customerId);
    }
}
