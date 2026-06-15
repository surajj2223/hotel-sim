package com.hotelops.core.product.vertical;

import com.hotelops.core.common.enums.CaptureMode;
import com.hotelops.core.common.enums.Vertical;
import com.hotelops.core.product.Product;
import com.hotelops.core.product.ProductRepository;
import com.hotelops.core.product.ProductSpa;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Package B — SPA vertical strategy.
 *
 * Registers for {@link Vertical#SPA} by implementing the marker
 * {@link VerticalStrategyRegistry.VerticalStrategyRegistration}; a class implementing only
 * {@link VerticalStrategy} would NOT be picked up by the registry.
 *
 * <ul>
 *   <li><b>availableCapacity</b> = {@code ProductSpa.concurrentSlots} minus the committed
 *       overlap count for the window. The overlap query is REUSED from
 *       {@link ProductRepository#countCommittedQuantity} — this strategy does not write its
 *       own overlap SQL (SCH-012).</li>
 *   <li><b>calculateUnitPrice</b> = the product's {@code base_price} snapshot (minor units).
 *       No yield/seasonal pricing.</li>
 *   <li><b>defaultCaptureMode</b> = {@link CaptureMode#IMMEDIATE} — spa sessions are
 *       authorised and captured together at booking (ENM-004).</li>
 * </ul>
 */
@Component
public class SpaStrategy implements VerticalStrategy,
        VerticalStrategyRegistry.VerticalStrategyRegistration {

    private final ProductRepository productRepository;

    public SpaStrategy(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public Vertical vertical() {
        return Vertical.SPA;
    }

    @Override
    public int availableCapacity(UUID productId, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        ProductSpa spa = spa(productId);
        long committed = productRepository.countCommittedQuantity(productId, startsAt, endsAt);
        return spa.getConcurrentSlots() - (int) committed;
    }

    @Override
    public long calculateUnitPrice(UUID productId, int quantity,
                                   OffsetDateTime startsAt, OffsetDateTime endsAt) {
        return spa(productId).getBasePrice();
    }

    /**
     * Spa line debt = base price × quantity. No nights factor — duration pricing is a
     * Rooms concern and must not leak into other verticals
     * (see {@code contracts/KNOWN_LIMITATION_ROOM_PRICING.md}).
     */
    @Override
    public long calculateLineAmount(UUID productId, int quantity,
                                    OffsetDateTime startsAt, OffsetDateTime endsAt) {
        return spa(productId).getBasePrice() * quantity;
    }

    @Override
    public CaptureMode defaultCaptureMode() {
        return CaptureMode.IMMEDIATE;
    }

    private ProductSpa spa(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + productId));
        if (!(product instanceof ProductSpa spa)) {
            throw new IllegalArgumentException(
                    "Product " + productId + " is not a SPA (vertical=" + product.getVertical() + ")");
        }
        return spa;
    }
}
