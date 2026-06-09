package com.hotelops.core.product;

import com.hotelops.core.AbstractDataJpaTest;
import com.hotelops.core.common.enums.Vertical;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests proving SCH-010, SCH-011, SCH-012, SCH-013, SCH-014.
 * INV-002 is tested via ProductService unit tests in InvariantTest.
 */
class ProductEntityTest extends AbstractDataJpaTest {

    @Autowired ProductRepository productRepository;

    // ── SCH-010 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_010_product_room_has_correct_vertical() {
        ProductRoom room = buildRoom(20000);
        ProductRoom saved = (ProductRoom) productRepository.save(room);
        assertThat(saved.getVertical()).isEqualTo(Vertical.ROOM);
        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void SCH_010_base_price_nonneg_check_constraint_rejects_negative() {
        ProductRoom room = buildRoom(-1);
        assertThatThrownBy(() -> productRepository.saveAndFlush(room))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void SCH_010_base_price_zero_is_allowed() {
        ProductRoom room = buildRoom(0);
        assertThatCode(() -> productRepository.save(room)).doesNotThrowAnyException();
    }

    // ── SCH-011 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_011_product_room_persists_with_all_fields() {
        ProductRoom room = buildRoom(15000);
        room.setFloorBand("HIGH");
        room.setBedType("KING");
        room.setMaxOccupancy(2);
        room.setQuiet(true);
        room.setRoomCount(5);
        ProductRoom saved = (ProductRoom) productRepository.save(room);
        assertThat(saved.getFloorBand()).isEqualTo("HIGH");
        assertThat(saved.getBedType()).isEqualTo("KING");
        assertThat(saved.isQuiet()).isTrue();
        assertThat(saved.getRoomCount()).isEqualTo(5);
    }

    @Test
    void SCH_011_room_count_nonneg_check() {
        ProductRoom room = buildRoom(10000);
        room.setRoomCount(-1);
        assertThatThrownBy(() -> productRepository.saveAndFlush(room))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void SCH_011_max_occupancy_at_least_1() {
        ProductRoom room = buildRoom(10000);
        room.setMaxOccupancy(0);
        assertThatThrownBy(() -> productRepository.saveAndFlush(room))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── SCH-012 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_012_product_spa_persists() {
        ProductSpa spa = buildSpa();
        ProductSpa saved = (ProductSpa) productRepository.save(spa);
        assertThat(saved.getVertical()).isEqualTo(Vertical.SPA);
        assertThat(saved.getTreatmentKind()).isEqualTo("MASSAGE_60");
    }

    @Test
    void SCH_012_spa_duration_must_be_positive() {
        ProductSpa spa = buildSpa();
        spa.setDurationMinutes(0);
        assertThatThrownBy(() -> productRepository.saveAndFlush(spa))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void SCH_012_spa_concurrent_slots_nonneg() {
        ProductSpa spa = buildSpa();
        spa.setConcurrentSlots(-1);
        assertThatThrownBy(() -> productRepository.saveAndFlush(spa))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── SCH-013 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_013_product_fnb_persists() {
        ProductFnb fnb = buildFnb();
        ProductFnb saved = (ProductFnb) productRepository.save(fnb);
        assertThat(saved.getVertical()).isEqualTo(Vertical.FNB);
        assertThat(saved.getServicePeriod()).isEqualTo("DINNER");
    }

    @Test
    void SCH_013_fnb_covers_capacity_nonneg() {
        ProductFnb fnb = buildFnb();
        fnb.setCoversCapacity(-1);
        assertThatThrownBy(() -> productRepository.saveAndFlush(fnb))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── SCH-014 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_014_product_event_persists() {
        ProductEvent event = buildEvent();
        ProductEvent saved = (ProductEvent) productRepository.save(event);
        assertThat(saved.getVertical()).isEqualTo(Vertical.EVENT);
        assertThat(saved.getCapacity()).isEqualTo(20);
    }

    @Test
    void SCH_014_event_capacity_nonneg() {
        ProductEvent event = buildEvent();
        event.setCapacity(-1);
        assertThatThrownBy(() -> productRepository.saveAndFlush(event))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void SCH_014_event_duration_must_be_positive() {
        ProductEvent event = buildEvent();
        event.setDurationMinutes(0);
        assertThatThrownBy(() -> productRepository.saveAndFlush(event))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ProductRoom buildRoom(long basePrice) {
        ProductRoom r = new ProductRoom();
        r.setName("Deluxe Room");
        r.setBasePrice(basePrice);
        r.setCurrency("GBP");
        r.setRoomCount(5);
        return r;
    }

    private ProductSpa buildSpa() {
        ProductSpa s = new ProductSpa();
        s.setName("60-min Massage");
        s.setBasePrice(8000);
        s.setCurrency("GBP");
        s.setTreatmentKind("MASSAGE_60");
        s.setDurationMinutes(60);
        s.setConcurrentSlots(3);
        return s;
    }

    private ProductFnb buildFnb() {
        ProductFnb f = new ProductFnb();
        f.setName("Dinner");
        f.setBasePrice(5000);
        f.setCurrency("GBP");
        f.setServicePeriod("DINNER");
        f.setCoversCapacity(40);
        return f;
    }

    private ProductEvent buildEvent() {
        ProductEvent e = new ProductEvent();
        e.setName("Hyde Park Horse Ride");
        e.setBasePrice(12000);
        e.setCurrency("GBP");
        e.setDepartsAt(OffsetDateTime.now().plusDays(7));
        e.setDurationMinutes(120);
        e.setCapacity(20);
        return e;
    }
}
