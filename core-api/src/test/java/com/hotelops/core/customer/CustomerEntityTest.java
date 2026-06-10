package com.hotelops.core.customer;

import com.hotelops.core.AbstractDataJpaTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests proving SCH-001, SCH-002, SCH-003, SCH-004.
 */
class CustomerEntityTest extends AbstractDataJpaTest {

    @Autowired CustomerRepository customerRepository;
    @Autowired CustomerPreferenceRepository preferenceRepository;

    // ── SCH-001 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_001_customer_persists_with_valid_shopper_reference() {
        Customer c = buildCustomer("SHPR-abcd1234efgh", "Alice Smith");
        Customer saved = customerRepository.save(c);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getShopperReference()).isEqualTo("SHPR-abcd1234efgh");
        assertThat(saved.getFullName()).isEqualTo("Alice Smith");
    }

    @Test
    void SCH_001_shopper_reference_must_be_unique() {
        customerRepository.save(buildCustomer("SHPR-uniquetoken00", "Alice"));
        Customer duplicate = buildCustomer("SHPR-uniquetoken00", "Bob");
        assertThatThrownBy(() -> customerRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void SCH_001_shopper_reference_format_check_constraint_rejects_bad_format() {
        // Missing SHPR- prefix
        Customer c = buildCustomer("BAD-reference", "Charlie");
        assertThatThrownBy(() -> customerRepository.saveAndFlush(c))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void SCH_001_shopper_reference_format_check_rejects_too_short_token() {
        // Token part has only 4 chars (minimum is 8)
        Customer c = buildCustomer("SHPR-1234", "Dave");
        assertThatThrownBy(() -> customerRepository.saveAndFlush(c))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── SCH-002 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_002_shopper_reference_column_is_updatable_false_at_jpa_level() throws Exception {
        // Verify the column's @Column annotation prevents JPA from including it in UPDATE
        var field = Customer.class.getDeclaredField("shopperReference");
        var col = field.getAnnotation(jakarta.persistence.Column.class);
        assertThat(col.updatable()).isFalse();
    }

    // ── SCH-003 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_003_customer_preference_persists() {
        Customer c = customerRepository.save(buildCustomer("SHPR-preftest0001", "Eve"));
        CustomerPreference p = new CustomerPreference();
        p.setCustomer(c);
        p.setPrefKey("floor");
        p.setPrefValue("high");
        CustomerPreference saved = preferenceRepository.save(p);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getPrefKey()).isEqualTo("floor");
    }

    @Test
    void SCH_003_preferences_cascade_deleted_with_customer() {
        Customer c = customerRepository.save(buildCustomer("SHPR-cascadetest01", "Frank"));
        CustomerPreference p = new CustomerPreference();
        p.setCustomer(c);
        c.getPreferences().add(p);   // sync both sides so the ORM cascade (cascade=ALL) removes it on delete
        p.setPrefKey("dietary");
        p.setPrefValue("vegan");
        preferenceRepository.save(p);
        assertThat(preferenceRepository.findByCustomerId(c.getId())).hasSize(1);

        customerRepository.deleteById(c.getId());
        assertThat(preferenceRepository.findByCustomerId(c.getId())).isEmpty();
    }

    // ── SCH-004 ──────────────────────────────────────────────────────────────

    @Test
    void SCH_004_unique_constraint_one_value_per_customer_key() {
        Customer c = customerRepository.save(buildCustomer("SHPR-uniqpref0001", "Grace"));
        savePreference(c, "floor", "high");
        CustomerPreference duplicate = new CustomerPreference();
        duplicate.setCustomer(c);
        duplicate.setPrefKey("floor");
        duplicate.setPrefValue("low");   // different value, same key — must be rejected
        assertThatThrownBy(() -> preferenceRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void SCH_004_different_keys_for_same_customer_are_allowed() {
        Customer c = customerRepository.save(buildCustomer("SHPR-multikey0001", "Heidi"));
        savePreference(c, "floor", "high");
        savePreference(c, "dietary", "vegan");
        assertThat(preferenceRepository.findByCustomerId(c.getId())).hasSize(2);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Customer buildCustomer(String shopperRef, String name) {
        Customer c = new Customer();
        // Use reflection to set the updatable=false field for test setup
        try {
            var field = Customer.class.getDeclaredField("shopperReference");
            field.setAccessible(true);
            field.set(c, shopperRef);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        c.setFullName(name);
        return c;
    }

    private CustomerPreference savePreference(Customer c, String key, String value) {
        CustomerPreference p = new CustomerPreference();
        p.setCustomer(c);
        p.setPrefKey(key);
        p.setPrefValue(value);
        return preferenceRepository.save(p);
    }
}
