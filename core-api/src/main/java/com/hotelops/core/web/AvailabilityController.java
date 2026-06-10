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
 * API-004 — room availability search. Stage 1 supports ROOM only; the path is shared with
 * later verticals. Availability and unit price are computed by the vertical strategy
 * (the merged RoomStrategy) via the registry — no pricing/availability logic lives here.
 *
 * {@code quantity} is accepted and validated (>= 1) for forward-compatibility; Stage 1
 * reports availableUnits for every active room and does not filter the result set by it.
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

        if (vertical != Vertical.ROOM) {
            throw new IllegalArgumentException(
                    "Stage 1 supports availability search for ROOM only; got " + vertical);
        }

        VerticalStrategy strategy = strategyRegistry.forVertical(Vertical.ROOM);

        return productService.findByVertical(Vertical.ROOM).stream()
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
