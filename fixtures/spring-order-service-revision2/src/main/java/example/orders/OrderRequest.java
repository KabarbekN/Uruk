package example.orders;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record OrderRequest(@Min(300) long amount, @NotBlank String customerId, boolean premium) {}
