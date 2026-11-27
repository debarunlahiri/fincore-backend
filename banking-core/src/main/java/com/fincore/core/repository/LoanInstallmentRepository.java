package com.fincore.core.repository;

import com.fincore.core.entity.LoanInstallment;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanInstallmentRepository extends JpaRepository<LoanInstallment, UUID> {

    List<LoanInstallment> findByLoanIdOrderByNumberAsc(UUID loanId);

    List<LoanInstallment> findByLoanIdAndStatusInOrderByNumberAsc(
            UUID loanId, Collection<LoanInstallment.Status> statuses);

    List<LoanInstallment> findByStatusAndDueDateBefore(
            LoanInstallment.Status status, LocalDate date);
}
