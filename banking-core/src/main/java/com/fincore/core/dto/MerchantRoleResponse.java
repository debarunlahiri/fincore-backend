package com.fincore.core.dto;

import com.fincore.core.entity.MerchantPermission;
import com.fincore.core.entity.MerchantRole;
import java.util.List;

public record MerchantRoleResponse(
        MerchantRole role, String name, List<MerchantPermission> permissions) {
    public static MerchantRoleResponse from(MerchantRole r) {
        return new MerchantRoleResponse(
                r, r.displayName(), r.permissions().stream().sorted().toList());
    }
}
