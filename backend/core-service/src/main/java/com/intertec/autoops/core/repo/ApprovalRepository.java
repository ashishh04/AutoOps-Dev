package com.intertec.autoops.core.repo;

import com.intertec.autoops.core.domain.Approval;
import com.intertec.autoops.core.domain.ApprovalStatus;
import com.intertec.autoops.core.domain.RunTargetType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApprovalRepository extends JpaRepository<Approval, Long> {

    Optional<Approval> findByIdAndTenantId(Long id, String tenantId);

    List<Approval> findTop200ByTenantIdOrderByCreatedAtDesc(String tenantId);

    List<Approval> findTop200ByTenantIdAndProjectIdOrderByCreatedAtDesc(String tenantId, Long projectId);

    /** Windowed, for the platform timeline — the same shape RunRepository uses. */
    List<Approval> findTop200ByTenantIdAndProjectIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            String tenantId, Long projectId, java.time.Instant since);

    boolean existsByTargetTypeAndTargetIdAndTenantIdAndStatus(
            RunTargetType targetType, Long targetId, String tenantId, ApprovalStatus status);

    /** Governance APPROVAL_SLA basis: pending requests older than the SLA cutoff. */
    List<Approval> findTop100ByTenantIdAndStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
            String tenantId, ApprovalStatus status, java.time.Instant cutoff);
}