package com.hotelops.core.customer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SCH-003, SCH-004 */
public interface CustomerPreferenceRepository extends JpaRepository<CustomerPreference, UUID> {

    List<CustomerPreference> findByCustomerId(UUID customerId);

    Optional<CustomerPreference> findByCustomerIdAndPrefKey(UUID customerId, String prefKey);

    void deleteByCustomerIdAndPrefKey(UUID customerId, String prefKey);
}
