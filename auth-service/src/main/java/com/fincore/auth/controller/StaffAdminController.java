package com.fincore.auth.controller;

import com.fincore.auth.dto.CreateStaffRequest;
import com.fincore.auth.dto.StaffResponse;
import com.fincore.auth.service.AuthService;
import com.fincore.security.model.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Staff (non-customer) accounts. Only a full ADMIN may create or list them. */
@RestController
@RequestMapping("/api/v1/admin/staff")
@PreAuthorize("hasRole('ADMIN')")
public class StaffAdminController {

    private final AuthService authService;

    public StaffAdminController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StaffResponse create(@AuthenticationPrincipal AuthenticatedUser admin, @Valid @RequestBody CreateStaffRequest r) {
        return StaffResponse.from(authService.createStaff(admin.username(), r.username(), r.email(), r.password(), r.role()));
    }

    @GetMapping
    public List<StaffResponse> list() {
        return authService.listStaff().stream().map(StaffResponse::from).toList();
    }
}
