package com.hotelops.core.product;

import com.hotelops.core.common.enums.Vertical;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** SCH-010..014 */
public interface ProductRepository extends JpaRepository<Product, UUID> {

    List<Product> findByVerticalAndActiveTrue(Vertical vertical);

    /**
     * Count of ACTIVE booking lines for a product that overlap the given window.
     * Used by vertical strategies to compute committed demand for availability checks.
     * Hits the partial index idx_line_product_window.
     */
    @Query("""
           SELECT COALESCE(SUM(bl.quantity), 0)
           FROM BookingLine bl
           WHERE bl.product.id = :productId
             AND bl.status = com.hotelops.core.common.enums.BookingLineStatus.ACTIVE
             AND bl.startsAt < :endsAt
             AND bl.endsAt > :startsAt
           """)
    long countCommittedQuantity(
            @Param("productId") UUID productId,
            @Param("startsAt") OffsetDateTime startsAt,
            @Param("endsAt") OffsetDateTime endsAt);
}
