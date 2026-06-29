package com.hotelops.core.product.vertical;

import com.hotelops.core.common.enums.CaptureMode;
import com.hotelops.core.common.enums.Vertical;
import com.hotelops.core.product.Product;
import com.hotelops.core.product.ProductFnb;
import com.hotelops.core.product.ProductRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Package B — F&amp;B (FNB) vertical strategy. Mirrors {@link SpaStrategy} exactly.
 *
 * Registers for {@link Vertical#FNB} by implementing the marker
 * {@link VerticalStrategyRegistry.VerticalStrategyRegistration}; a class implementing only
 * {@link VerticalStrategy} would NOT be picked up by the registry.
 *
 * <ul>
 *   <li><b>availableCapacity</b> = {@code ProductFnb.coversCapacity} minus the committed
 *       overlap count for the window. The overlap query is REUSED from
 *       {@link ProductRepository#countCommittedQuantity} — this strategy does not write its
 *       own overlap SQL (SCH-013).</li>
 *   <li><b>calculateUnitPrice</b> = the product's {@code base_price} snapshot (minor units).
 *       No yield/seasonal pricing.</li>
 *   <li><b>calculateLineAmount</b> = base price × quantity. No nights factor — duration
 *       pricing is a Rooms concern (see {@code contracts/KNOWN_LIMITATION_ROOM_PRICING.md}).</li>
 *   <li><b>defaultCaptureMode</b> = {@link CaptureMode#IMMEDIATE} — F&amp;B is the charter's
 *       canonical authorise-and-capture-together vertical (charter §6/§11, ENM-004).</li>
 * </ul>
 */
@Component
public class FnbStrategy implements VerticalStrategy,
        VerticalStrategyRegistry.VerticalStrategyRegistration {

    private final ProductRepository productRepository;

    public FnbStrategy(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public Vertical vertical() {
        return Vertical.FNB;
    }

    @Override
    public int availableCapacity(UUID productId, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        ProductFnb fnb = fnb(productId);
        long committed = productRepository.countCommittedQuantity(productId, startsAt, endsAt);
        return fnb.getCoversCapacity() - (int) committed;
    }

    @Override
    public long calculateUnitPrice(UUID productId, int quantity,
                                   OffsetDateTime startsAt, OffsetDateTime endsAt) {
        return fnb(productId).getBasePrice();
    }

    /**
     * F&amp;B line debt = base price × quantity. No nights factor — duration pricing is a
     * Rooms concern and must not leak into other verticals
     * (see {@code contracts/KNOWN_LIMITATION_ROOM_PRICING.md}).
     */
    @Override
    public long calculateLineAmount(UUID productId, int quantity,
                                    OffsetDateTime startsAt, OffsetDateTime endsAt) {
        return fnb(productId).getBasePrice() * quantity;
    }

    @Override
    public CaptureMode defaultCaptureMode() {
        return CaptureMode.IMMEDIATE;
    }

    private ProductFnb fnb(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + productId));
        if (!(product instanceof ProductFnb fnb)) {
            throw new IllegalArgumentException(
                    "Product " + productId + " is not an FNB (vertical=" + product.getVertical() + ")");
        }
        return fnb;
    }
}
