package com.fincore.core.controller;

import com.fincore.core.dto.AdminMerchantResponse;
import com.fincore.core.dto.AdminMerchantSummaryResponse;
import com.fincore.core.dto.SettlementResponse;
import com.fincore.core.entity.MerchantStatus;
import com.fincore.core.service.MerchantAdminService;
import com.fincore.core.service.MerchantService;
import com.fincore.security.model.AuthenticatedUser;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Reading is open to all staff (compliance is read-only); acting needs ADMIN or OPERATIONS. */
@RestController
@RequestMapping("/api/v1/admin/merchants")
public class AdminMerchantController {

    private final MerchantAdminService admin;
    private final MerchantService merchants;

    public AdminMerchantController(MerchantAdminService admin, MerchantService merchants) {
        this.admin = admin;
        this.merchants = merchants;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATIONS', 'COMPLIANCE')")
    public AdminMerchantSummaryResponse summary() {
        return AdminMerchantSummaryResponse.from(admin.summary());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATIONS', 'COMPLIANCE')")
    public List<AdminMerchantResponse> list(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) MerchantStatus status) {
        return admin.search(query, status).stream().map(AdminMerchantResponse::from).toList();
    }

    @PostMapping("/{merchantCode}/suspend")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATIONS')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspend(
            @AuthenticationPrincipal AuthenticatedUser staff, @PathVariable String merchantCode) {
        admin.setSuspended(merchantCode, true, staff.username());
    }

    @PostMapping("/{merchantCode}/reactivate")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATIONS')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reactivate(
            @AuthenticationPrincipal AuthenticatedUser staff, @PathVariable String merchantCode) {
        admin.setSuspended(merchantCode, false, staff.username());
    }

    @PostMapping("/{merchantCode}/settle")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATIONS')")
    public SettlementResponse settle(
            @AuthenticationPrincipal AuthenticatedUser staff, @PathVariable String merchantCode) {
        return SettlementResponse.from(merchants.settleByCode(merchantCode, staff.username()));
    }

    /** Runs the daily settlement job's work right now for every merchant that is due. */
    @PostMapping("/settle-due")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATIONS')")
    public Map<String, Integer> settleDue() {
        return Map.of("settled", merchants.settleAllDue());
    }
}
