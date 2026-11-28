package com.fincore.auth.service;

import com.fincore.audit.service.AuditService;
import com.fincore.auth.entity.LoginEvent;
import com.fincore.auth.entity.RefreshToken;
import com.fincore.auth.entity.User;
import com.fincore.auth.event.UserRegistered;
import com.fincore.auth.repository.LoginEventRepository;
import com.fincore.auth.repository.RefreshTokenRepository;
import com.fincore.auth.repository.UserRepository;
import com.fincore.common.exception.FincoreException;
import com.fincore.outbox.service.OutboxService;
import com.fincore.security.jwt.JwtService;
import com.fincore.security.model.Role;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    public record RegistrationData(
        String username,
        String email,
        String password,
        String fullName,
        String phone,
        LocalDate dateOfBirth) {
    }

    public record Tokens(String accessToken, String refreshToken, long expiresInSeconds) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService audit;
    private final OutboxService outbox;
    private final Duration refreshTtl;
    private final LoginEventRepository loginEvents;
    private final int maxFailedAttempts;
    private final boolean singleSession;
    private final Duration lockDuration;

    public AuthService(
        UserRepository users,
        RefreshTokenRepository refreshTokens,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        AuditService audit,
        OutboxService outbox,
        LoginEventRepository loginEvents,
        @Value("${fincore.security.jwt.refresh-ttl:7d}") Duration refreshTtl,
        @Value("${fincore.security.max-failed-attempts:5}") int maxFailedAttempts,
        @Value("${fincore.security.single-session:true}") boolean singleSession,
        @Value("${fincore.security.lock-duration:15m}") Duration lockDuration) {
        this.loginEvents = loginEvents;
        this.maxFailedAttempts = maxFailedAttempts;
        this.singleSession = singleSession;
        this.lockDuration = lockDuration;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.audit = audit;
        this.outbox = outbox;
        this.refreshTtl = refreshTtl;
    }

    /**
     * Creates the login identity and publishes {@code UserRegistered} through the outbox in the
     * same transaction. The customer profile is created asynchronously by the customer-service
     * (eventual consistency): if Kafka or the customer-service is down the user can still log in,
     * and the profile appears once the event is processed.
     */
    @Transactional
    public Tokens register(RegistrationData data) {
        String username = data.username().trim().toLowerCase();
        String email = data.email().trim().toLowerCase();
        if (users.existsByUsername(username)) {
            throw FincoreException.conflict("USERNAME_TAKEN", "Username is already taken");
        }
        if (users.existsByEmail(email)) {
            throw FincoreException.conflict("EMAIL_TAKEN", "Email is already registered");
        }
        User user =
            users.save(
                new User(
                    username,
                    email,
                    passwordEncoder.encode(data.password()),
                    Role.CUSTOMER));
        outbox.record(
            "USER",
            user.getId(),
            "UserRegistered",
            new UserRegistered(
                user.getId(),
                username,
                email,
                data.fullName(),
                data.phone(),
                data.dateOfBirth(),
                Instant.now()));
        return issueTokens(user, ClientInfo.UNKNOWN);
    }

    @Transactional
    public User createAdminIfAbsent(String username, String email, String rawPassword) {
        return users.findByUsername(username)
            .orElseGet(
                () ->
                    users.save(
                        new User(
                            username,
                            email,
                            passwordEncoder.encode(rawPassword),
                            Role.ADMIN)));
    }

    /**
     * Creates a staff login (ADMIN, OPERATIONS or COMPLIANCE). No customer profile is created for
     * staff.
     */
    @Transactional
    public User createStaff(
        String actor, String username, String email, String rawPassword, Role role) {
        if (!role.isStaff()) {
            throw FincoreException.badRequest("INVALID_ROLE", "Role must be a staff role");
        }
        String u = username.trim().toLowerCase();
        String e = email.trim().toLowerCase();
        if (users.existsByUsername(u)) {
            throw FincoreException.conflict("USERNAME_TAKEN", "Username is already taken");
        }
        if (users.existsByEmail(e)) {
            throw FincoreException.conflict("EMAIL_TAKEN", "Email is already registered");
        }
        User user = users.save(new User(u, e, passwordEncoder.encode(rawPassword), role));
        audit.record(actor, "STAFF_CREATED", "USER", user.getId().toString(), "role=" + role);
        return user;
    }

    @Transactional(readOnly = true)
    public java.util.List<User> listStaff() {
        return users.findByRoleNotOrderByUsername(Role.CUSTOMER);
    }

    public record ClientInfo(String ip, String userAgent) {
        public static final ClientInfo UNKNOWN = new ClientInfo(null, null);
    }

    @Transactional(noRollbackFor = FincoreException.class)
    public Tokens login(String username, String password) {
        return login(username, password, ClientInfo.UNKNOWN);
    }

    /**
     * noRollbackFor: a failed attempt must still commit its counter and lock, otherwise lockout
     * would never trigger. Five bad passwords lock the login for the configured duration; a
     * successful login resets the counter.
     */
    @Transactional(noRollbackFor = FincoreException.class)
    public Tokens login(String username, String password, ClientInfo client) {
        User user = users.findByUsername(username.trim().toLowerCase()).orElse(null);
        if (user != null && user.isLocked()) {
            loginEvents.save(
                new LoginEvent(
                    user.getId(),
                    user.getUsername(),
                    false,
                    "LOCKED",
                    client.ip(),
                    client.userAgent()));
            throw new FincoreException(
                org.springframework.http.HttpStatus.LOCKED,
                "ACCOUNT_LOCKED",
                "Too many failed attempts. Try again after " + user.getLockedUntil());
        }
        // one generic message for unknown user / bad password / disabled so we do not leak which
        // usernames exist
        if (user == null
            || !user.isEnabled()
            || !passwordEncoder.matches(password, user.getPasswordHash())) {
            boolean locked =
                user != null
                    && user.isEnabled()
                    && user.recordFailedLogin(maxFailedAttempts, lockDuration);
            loginEvents.save(
                new LoginEvent(
                    user == null ? null : user.getId(),
                    username,
                    false,
                    "BAD_CREDENTIALS",
                    client.ip(),
                    client.userAgent()));
            audit.record(username, locked ? "ACCOUNT_LOCKED" : "LOGIN_FAILED", "USER", null, null);
            throw FincoreException.unauthorized("Invalid credentials");
        }
        user.recordSuccessfulLogin();
        loginEvents.save(
            new LoginEvent(
                user.getId(),
                user.getUsername(),
                true,
                null,
                client.ip(),
                client.userAgent()));
        audit.record(user.getUsername(), "LOGIN_SUCCEEDED", "USER", user.getId().toString(), null);
        if (singleSession && refreshTokens.deleteAllForUser(user.getId()) > 0) {
            // one active device per user: signing in here ends the session on any other device
            audit.record(
                user.getUsername(),
                "SESSION_REPLACED",
                "USER",
                user.getId().toString(),
                client.ip());
        }
        return issueTokens(user, client);
    }

    @Transactional(readOnly = true)
    public java.util.List<LoginEvent> loginHistory(UUID userId, int limit) {
        return loginEvents.findByUserIdOrderByCreatedAtDesc(
            userId,
            org.springframework.data.domain.PageRequest.of(
                0, Math.min(Math.max(limit, 1), 100)));
    }

    /**
     * Verifies the current password, sets the new one and signs the user out everywhere.
     */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword) {
        User user =
            users.findById(userId)
                .orElseThrow(() -> FincoreException.notFound("User not found"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            audit.record(
                user.getUsername(), "PASSWORD_CHANGE_FAILED", "USER", userId.toString(), null);
            throw FincoreException.badRequest("WRONG_PASSWORD", "Current password is incorrect");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw FincoreException.badRequest(
                "PASSWORD_REUSED", "New password must differ from the current one");
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        refreshTokens.revokeAllForUser(userId);
        audit.record(user.getUsername(), "PASSWORD_CHANGED", "USER", userId.toString(), null);
    }

    @Transactional(readOnly = true)
    public java.util.List<RefreshToken> activeSessions(UUID userId) {
        return refreshTokens.findByUserIdAndRevokedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
            userId, Instant.now());
    }

    @Transactional
    public void revokeSession(UUID userId, UUID sessionId) {
        refreshTokens
            .findByIdAndUserId(sessionId, userId)
            .orElseThrow(() -> FincoreException.notFound("Session not found"))
            .revoke();
    }

    @Transactional
    public int revokeAllSessions(UUID userId) {
        return refreshTokens.revokeAllForUser(userId);
    }

    /**
     * Rotates the refresh token. Presenting an already-revoked token means it was stolen or
     * replayed, so every session of that user is revoked. noRollbackFor keeps that revocation
     * committed.
     */
    @Transactional(noRollbackFor = FincoreException.class)
    public Tokens refresh(String rawRefreshToken) {
        RefreshToken stored =
            refreshTokens
                .findByTokenHash(hash(rawRefreshToken))
                .orElseThrow(() -> FincoreException.unauthorized("Invalid refresh token"));
        if (stored.isRevoked()) {
            refreshTokens.revokeAllForUser(stored.getUserId());
            throw FincoreException.unauthorized(
                "Refresh token reuse detected, all sessions revoked");
        }
        if (stored.isExpired()) {
            throw FincoreException.unauthorized("Refresh token expired");
        }
        User user =
            users.findById(stored.getUserId())
                .filter(User::isEnabled)
                .orElseThrow(() -> FincoreException.unauthorized("Invalid refresh token"));
        stored.revoke();
        return issueTokens(user, new ClientInfo(stored.getIpAddress(), stored.getUserAgent()));
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.findByTokenHash(hash(rawRefreshToken)).ifPresent(RefreshToken::revoke);
    }

    private Tokens issueTokens(User user, ClientInfo client) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.save(
            new RefreshToken(
                user.getId(),
                hash(refresh),
                Instant.now().plus(refreshTtl),
                client.ip(),
                client.userAgent()));
        String access =
            jwtService.generateAccessToken(user.getId(), user.getUsername(), user.getRole());
        return new Tokens(access, refresh, jwtService.accessTtl().toSeconds());
    }

    private static String hash(String token) {
        try {
            byte[] digest =
                MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
