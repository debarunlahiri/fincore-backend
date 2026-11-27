package com.fincore.core.dto;

import java.util.List;

public record LoanDetailResponse(LoanResponse loan, List<InstallmentResponse> schedule) {}
