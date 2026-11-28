package com.fincore.core.dto;

import com.fincore.core.entity.Merchant;
import java.math.BigDecimal;

/**
 * What a paying customer may see about a merchant before paying. {@code lockedAmount} is set only
 * when the link carried a valid signature: the payer then cannot change the amount.
 */
public record MerchantLookupResponse(
        String merchantCode, String businessName, String category, BigDecimal lockedAmount) {
    public static MerchantLookupResponse from(Merchant m) {
        return new MerchantLookupResponse(
                m.getReference(), m.getBusinessName(), m.getCategory(), null);
    }

    public static MerchantLookupResponse from(Merchant m, BigDecimal lockedAmount) {
        return new MerchantLookupResponse(
                m.getReference(), m.getBusinessName(), m.getCategory(), lockedAmount);
    }
}
