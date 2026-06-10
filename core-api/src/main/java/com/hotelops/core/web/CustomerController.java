package com.hotelops.core.web;

import com.hotelops.core.customer.CustomerService;
import com.hotelops.core.web.dto.CustomerCreateRequest;
import com.hotelops.core.web.dto.CustomerResponse;
import com.hotelops.core.web.dto.PreferenceResponse;
import com.hotelops.core.web.dto.PreferenceValue;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Customer endpoints — API-001 (create), API-002 (get), API-003 (upsert preference).
 * Thin HTTP boundary over {@link CustomerService}; entities are mapped to DTOs only.
 */
@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerService customerService;
    private final DtoMapper mapper;

    public CustomerController(CustomerService customerService, DtoMapper mapper) {
        this.customerService = customerService;
        this.mapper = mapper;
    }

    /** API-001: create a customer; the server mints shopperReference (INV-001). */
    @PostMapping
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerCreateRequest request) {
        var customer = customerService.createCustomer(request.fullName(), request.email(), request.phone());
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toCustomerResponse(customer));
    }

    /** API-002: fetch a single customer by id. */
    @GetMapping("/{customerId}")
    public CustomerResponse get(@PathVariable UUID customerId) {
        return mapper.toCustomerResponse(customerService.getById(customerId));
    }

    /** API-003: upsert one preference value for a key (one value per key, SCH-004). */
    @PutMapping("/{customerId}/preferences/{key}")
    public PreferenceResponse setPreference(@PathVariable UUID customerId,
                                            @PathVariable String key,
                                            @Valid @RequestBody PreferenceValue body) {
        var pref = customerService.setPreference(customerId, key, body.value());
        return mapper.toPreferenceResponse(pref);
    }
}
