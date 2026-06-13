package com.hotelops.core.web;

import com.hotelops.core.common.enums.Vertical;
import com.hotelops.core.product.Product;
import com.hotelops.core.product.ProductService;
import com.hotelops.core.product.vertical.VerticalStrategy;
import com.hotelops.core.product.vertical.VerticalStrategyRegistry;
import com.hotelops.core.web.dto.AvailabilityResult;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * API-004 — vertical availability search. Delegates to the registered VerticalStrategy — no
 * vertical-specific logic lives here. Availability and unit price are computed by the
 * strategy resolved from the registry for the requested vertical.
 *
 * {@code quantity} is accepted and validated (>= 1) for forward-compatibility; the endpoint
 * reports availableUnits for every active product of the requested vertical and does not
 * filter the result set by quantity.
 */
@RestController
@Validated
public class AvailabilityController {

    private final ProductService productService;
    private final VerticalStrategyRegistry strategyRegistry;
    private final DtoMapper mapper;

    public AvailabilityController(ProductService productService,
                                  VerticalStrategyRegistry strategyRegistry,
                                  DtoMapper mapper) {
        this.productService = productService;
        this.strategyRegistry = strategyRegistry;
        this.mapper = mapper;
    }

    @GetMapping("/availability")
    public List<AvailabilityResult> search(
            @RequestParam Vertical vertical,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startsAt,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endsAt,
            @RequestParam(defaultValue = "1") @Min(1) int quantity) {

        VerticalStrategy strategy = strategyRegistry.forVertical(vertical);

        return productService.findByVertical(vertical).stream()
                .map(product -> toResult(strategy, product, startsAt, endsAt, quantity))
                .toList();
    }

    private AvailabilityResult toResult(VerticalStrategy strategy, Product product,
                                        OffsetDateTime startsAt, OffsetDateTime endsAt, int quantity) {
        long unitPrice = strategy.calculateUnitPrice(product.getId(), quantity, startsAt, endsAt);
        int availableUnits = strategy.availableCapacity(product.getId(), startsAt, endsAt);
        return mapper.toAvailabilityResult(product, unitPrice, availableUnits);
    }
}
