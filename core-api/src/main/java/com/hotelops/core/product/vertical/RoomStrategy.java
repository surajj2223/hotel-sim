package com.hotelops.core.product.vertical;

import com.hotelops.core.common.enums.CaptureMode;
import com.hotelops.core.common.enums.Vertical;
import com.hotelops.core.product.Product;
import com.hotelops.core.product.ProductRepository;
import com.hotelops.core.product.ProductRoom;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Package B — ROOM vertical strategy.
 *
 * Registers for {@link Vertical#ROOM} by implementing the marker
 * {@link VerticalStrategyRegistry.VerticalStrategyRegistration}; a class implementing only
 * {@link VerticalStrategy} would NOT be picked up by the registry.
 *
 * <ul>
 *   <li><b>availableCapacity</b> = {@code ProductRoom.roomCount} minus the committed
 *       overlap count for the window. The overlap query is REUSED from
 *       {@link ProductRepository#countCommittedQuantity} (the single source documented for
 *       vertical strategies) — this strategy does not write its own overlap SQL.</li>
 *   <li><b>calculateUnitPrice</b> = the product's {@code base_price} snapshot (minor units).
 *       Stage 1 has no yield/seasonal pricing.</li>
 *   <li><b>defaultCaptureMode</b> = {@link CaptureMode#MANUAL} — rooms hold the card at
 *       booking and capture at checkout (ENM-004).</li>
 * </ul>
 */
@Component
public class RoomStrategy implements VerticalStrategy,
        VerticalStrategyRegistry.VerticalStrategyRegistration {

    private final ProductRepository productRepository;

    public RoomStrategy(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public Vertical vertical() {
        return Vertical.ROOM;
    }

    @Override
    public int availableCapacity(UUID productId, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        ProductRoom room = room(productId);
        long committed = productRepository.countCommittedQuantity(productId, startsAt, endsAt);
        return room.getRoomCount() - (int) committed;
    }

    @Override
    public long calculateUnitPrice(UUID productId, int quantity,
                                   OffsetDateTime startsAt, OffsetDateTime endsAt) {
        return room(productId).getBasePrice();
    }

    @Override
    public CaptureMode defaultCaptureMode() {
        return CaptureMode.MANUAL;
    }

    private ProductRoom room(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + productId));
        if (!(product instanceof ProductRoom room)) {
            throw new IllegalArgumentException(
                    "Product " + productId + " is not a ROOM (vertical=" + product.getVertical() + ")");
        }
        return room;
    }
}
