package com.hotelops.core.customer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * INV-001 — shopperReference is minted once at creation and NEVER changed.
 *
 * All customer writes go through this service; the service enforces immutability of
 * {@code shopperReference}.  The entity's {@code shopperReference} column is
 * {@code updatable=false} at the JPA level as an additional guard, but the primary
 * enforcement is here.
 */
@Service
@Transactional
public class CustomerService {

    /** Prefix mandated by SCH-002 / chk_shopper_reference_format. */
    private static final String SHOPPER_REF_PREFIX = "SHPR-";

    private final CustomerRepository customerRepository;
    private final CustomerPreferenceRepository preferenceRepository;

    public CustomerService(CustomerRepository customerRepository,
                           CustomerPreferenceRepository preferenceRepository) {
        this.customerRepository = customerRepository;
        this.preferenceRepository = preferenceRepository;
    }

    /**
     * Create a new customer.  Mints an immutable shopperReference here — never
     * elsewhere.  Format: SHPR-{32 hex chars}, satisfying the DB CHECK constraint.
     */
    public Customer createCustomer(String fullName, String email, String phone) {
        Customer c = new Customer();
        c.setShopperReference(mintShopperReference());
        c.setFullName(fullName);
        c.setEmail(email);
        c.setPhone(phone);
        return customerRepository.save(c);
    }

    /**
     * Update mutable fields (fullName, email, phone).
     * INV-001: shopperReference is ignored in the request — it is NEVER changed.
     */
    public Customer updateCustomer(UUID id, String fullName, String email, String phone) {
        Customer c = customerRepository.findById(id)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Customer not found: " + id));
        // INV-001: shopperReference is intentionally NOT a parameter here
        if (fullName != null) c.setFullName(fullName);
        if (email != null)    c.setEmail(email);
        if (phone != null)    c.setPhone(phone);
        return customerRepository.save(c);
    }

    /** Upsert a customer preference (SCH-004: one value per key). */
    public CustomerPreference setPreference(UUID customerId, String key, String value) {
        Customer c = customerRepository.findById(customerId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Customer not found: " + customerId));
        CustomerPreference pref = preferenceRepository
                .findByCustomerIdAndPrefKey(customerId, key)
                .orElseGet(() -> {
                    CustomerPreference p = new CustomerPreference();
                    p.setCustomer(c);
                    p.setPrefKey(key);
                    return p;
                });
        pref.setPrefValue(value);
        return preferenceRepository.save(pref);
    }

    @Transactional(readOnly = true)
    public Customer getById(UUID id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Customer not found: " + id));
    }

    @Transactional(readOnly = true)
    public List<Customer> searchByName(String name) {
        return customerRepository.searchByName(name);
    }

    @Transactional(readOnly = true)
    public List<CustomerPreference> getPreferences(UUID customerId) {
        return preferenceRepository.findByCustomerId(customerId);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Mint a shopperReference satisfying '^SHPR-[A-Za-z0-9_-]{8,}$'. */
    public static String mintShopperReference() {
        // UUID without dashes gives 32 alphanumeric characters — well over the 8-char minimum
        return SHOPPER_REF_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }
}
