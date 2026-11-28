package com.fincore.core.entity;

import java.util.EnumSet;
import java.util.Set;

import static com.fincore.core.entity.MerchantPermission.*;

/**
 * Sub-roles inside one merchant (a main merchant, a franchise outlet or a standalone merchant). The
 * registering customer is the OWNER; the owner invites everyone else. Money balances and the
 * settlement account are only shown to roles with VIEW_SETTLEMENTS.
 */
public enum MerchantRole {
    OWNER("Owner", EnumSet.allOf(MerchantPermission.class)),
    MANAGER(
            "Manager",
            EnumSet.of(
                    VIEW_PAYMENTS,
                    REFUND,
                    VIEW_SETTLEMENTS,
                    SETTLE,
                    VIEW_NETWORK,
                    MANAGE_NETWORK)),
    ACCOUNTANT(
            "Accountant", EnumSet.of(VIEW_PAYMENTS, VIEW_SETTLEMENTS, SETTLE, VIEW_NETWORK)),
    CASHIER("Cashier", EnumSet.of(VIEW_PAYMENTS)),
    VIEWER("Viewer", EnumSet.of(VIEW_PAYMENTS, VIEW_SETTLEMENTS, VIEW_NETWORK));

    private final String displayName;
    private final Set<MerchantPermission> permissions;

    MerchantRole(String displayName, Set<MerchantPermission> permissions) {
        this.displayName = displayName;
        this.permissions = permissions;
    }

    public String displayName() {
        return displayName;
    }

    public Set<MerchantPermission> permissions() {
        return permissions;
    }

    public boolean can(MerchantPermission permission) {
        return permissions.contains(permission);
    }
}
