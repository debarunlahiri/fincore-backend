package com.fincore.auth.controller;

import com.fincore.auth.dto.ChangePasswordRequest;
import com.fincore.auth.dto.LoginEventResponse;
import com.fincore.auth.dto.SessionResponse;
import com.fincore.auth.service.AuthService;
import com.fincore.security.model.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Account-security self service for any signed-in user: password, devices, login activity. */
@RestController
@RequestMapping("/api/v1/security")
public class SecurityController {

    private final AuthService authService;

    public SecurityController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ChangePasswordRequest r) {
        authService.changePassword(user.userId(), r.currentPassword(), r.newPassword());
    }

    @GetMapping("/login-history")
    public List<LoginEventResponse> loginHistory(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "20") int limit) {
        return authService.loginHistory(user.userId(), limit).stream()
                .map(LoginEventResponse::from)
                .toList();
    }

    @GetMapping("/sessions")
    public List<SessionResponse> sessions(@AuthenticationPrincipal AuthenticatedUser user) {
        return authService.activeSessions(user.userId()).stream()
                .map(SessionResponse::from)
                .toList();
    }

    /**
     * Signs the user out of every device (with one active device per user, that is the current
     * one).
     */
    @DeleteMapping("/sessions")
    public Map<String, Integer> revokeAll(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("revoked", authService.revokeAllSessions(user.userId()));
    }
}
