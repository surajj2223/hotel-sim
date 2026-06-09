package com.hotelops.core.product;

import com.hotelops.core.common.enums.Vertical;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * INV-002 — every product row has EXACTLY ONE child row in the table matching its
 * vertical.  No zero-child, no multi-child.
 *
 * JTI does not enforce this in the DB; this service creates base + child atomically in
 * the SAME transaction and rejects operations that would violate it.
 */
@Service
@Transactional
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /** INV-002: create a ROOM product atomically (base + child in one transaction). */
    public ProductRoom createRoom(String name, long basePricePence, String currency,
                                  String floorBand, String bedType, int maxOccupancy,
                                  boolean quiet, int roomCount) {
        ProductRoom p = new ProductRoom();
        fillBase(p, name, basePricePence, currency);
        p.setFloorBand(floorBand);
        p.setBedType(bedType);
        p.setMaxOccupancy(maxOccupancy);
        p.setQuiet(quiet);
        p.setRoomCount(roomCount);
        return (ProductRoom) productRepository.save(p);
    }

    /** INV-002: create a SPA product atomically. */
    public ProductSpa createSpa(String name, long basePricePence, String currency,
                                String treatmentKind, int durationMinutes,
                                String therapistGender, int concurrentSlots) {
        ProductSpa p = new ProductSpa();
        fillBase(p, name, basePricePence, currency);
        p.setTreatmentKind(treatmentKind);
        p.setDurationMinutes(durationMinutes);
        p.setTherapistGender(therapistGender);
        p.setConcurrentSlots(concurrentSlots);
        return (ProductSpa) productRepository.save(p);
    }

    /** INV-002: create an F&B product atomically. */
    public ProductFnb createFnb(String name, long basePricePence, String currency,
                                String servicePeriod, int coversCapacity, int seatingMinutes) {
        ProductFnb p = new ProductFnb();
        fillBase(p, name, basePricePence, currency);
        p.setServicePeriod(servicePeriod);
        p.setCoversCapacity(coversCapacity);
        p.setSeatingMinutes(seatingMinutes);
        return (ProductFnb) productRepository.save(p);
    }

    /** INV-002: create an EVENT product atomically. */
    public ProductEvent createEvent(String name, long basePricePence, String currency,
                                    java.time.OffsetDateTime departsAt, int durationMinutes,
                                    int capacity, String location) {
        ProductEvent p = new ProductEvent();
        fillBase(p, name, basePricePence, currency);
        p.setDepartsAt(departsAt);
        p.setDurationMinutes(durationMinutes);
        p.setCapacity(capacity);
        p.setLocation(location);
        return (ProductEvent) productRepository.save(p);
    }

    @Transactional(readOnly = true)
    public Product getById(UUID id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + id));
    }

    @Transactional(readOnly = true)
    public java.util.List<Product> findByVertical(Vertical vertical) {
        return productRepository.findByVerticalAndActiveTrue(vertical);
    }

    // -------------------------------------------------------------------------

    private void fillBase(Product p, String name, long basePricePence, String currency) {
        p.setName(name);
        p.setBasePrice(basePricePence);
        p.setCurrency(currency != null ? currency : "GBP");
        p.setActive(true);
    }
}
