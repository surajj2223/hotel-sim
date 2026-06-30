package com.hotelops.core.web;

import com.hotelops.core.reporting.ReportingService;
import com.hotelops.core.web.dto.RevenueReport;
import com.hotelops.core.web.dto.UnpaidBookings;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * Reporting reads — API-016 getRevenue, API-017 listUnpaidBookings (charter §9).
 *
 * Thin HTTP boundary over {@link ReportingService}; entities are never serialised (the service
 * returns DTOs). Read-only, no human-auth gate (no repercussions). Missing/unparseable {@code from}
 * or {@code to} fall through to {@code GlobalExceptionHandler} → 400 (the contract's BadRequest).
 */
@RestController
@RequestMapping("/reports")
public class ReportingController {

    private final ReportingService reportingService;

    public ReportingController(ReportingService reportingService) {
        this.reportingService = reportingService;
    }

    /** API-016: revenue split by vertical over a half-open [from, to) window on posting time. */
    @GetMapping("/revenue")
    public RevenueReport getRevenue(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return reportingService.getRevenue(from, to);
    }

    /** API-017: the full unpaid-bookings worklist (total_amount > amount_paid), no params. */
    @GetMapping("/unpaid-bookings")
    public UnpaidBookings listUnpaidBookings() {
        return reportingService.listUnpaid();
    }
}
