package com.fincore.auth.dto;

import com.fincore.auth.entity.User;
import com.fincore.security.model.Role;
import java.util.UUID;

public record StaffResponse(UUID id, String username, String email, Role role, boolean enabled) {

    public static StaffResponse from(User u) {
        return new StaffResponse(
                u.getId(), u.getUsername(), u.getEmail(), u.getRole(), u.isEnabled());
    }
}
