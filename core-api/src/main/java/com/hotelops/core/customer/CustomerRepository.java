package com.hotelops.core.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** SCH-001, SCH-002 */
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByShopperReference(String shopperReference);

    /** Case-insensitive name search using the idx_customer_full_name index. */
    @Query("SELECT c FROM Customer c WHERE lower(c.fullName) LIKE lower(concat('%', :name, '%'))")
    List<Customer> searchByName(@Param("name") String name);

    boolean existsByEmail(String email);
}
