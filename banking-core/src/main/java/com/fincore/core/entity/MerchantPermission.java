package com.fincore.core.entity;

/** What a member of a merchant's team may do. Roles are bundles of these, see {@link MerchantRole}. */
public enum MerchantPermission {
    VIEW_PAYMENTS,
    REFUND,
    VIEW_SETTLEMENTS,
    SETTLE,
    VIEW_NETWORK,
    MANAGE_NETWORK,
    MANAGE_PLAN,
    MANAGE_TEAM
}
