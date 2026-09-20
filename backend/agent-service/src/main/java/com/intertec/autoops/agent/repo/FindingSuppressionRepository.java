package com.intertec.autoops.agent.repo;

import com.intertec.autoops.agent.domain.FindingSuppression;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FindingSuppressionRepository extends JpaRepository<FindingSuppression, Long> {

    /**
     * Every un-revoked suppression for a tenant.
     *
     * <p>Read whole and filtered in memory rather than matched in SQL. The
     * scope rules are a precedence ladder across four different shapes of
     * target, and expressing that as one query means four OR-ed predicates
     * with nullable columns on both sides — which is exactly the sort of
     * clause that silently matches everything when one side is null. The set
     * is small (a tenant has tens of these, not millions) and the rule is
     * worth being able to read.
     */
    List<FindingSuppression> findByTenantIdAndRevokedAtIsNull(String tenantId);

    List<FindingSuppression> findByTenantIdAndFindingIdAndRevokedAtIsNull(
            String tenantId, Long findingId);
}
