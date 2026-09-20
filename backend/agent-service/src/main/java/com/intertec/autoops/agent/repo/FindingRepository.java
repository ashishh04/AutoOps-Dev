package com.intertec.autoops.agent.repo;

import com.intertec.autoops.agent.domain.Finding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FindingRepository extends JpaRepository<Finding, Long> {

    /** The identity lookup. Tenant + key + key VERSION, never key alone. */
    Optional<Finding> findByTenantIdAndIdempotencyKeyAndIdempotencyKeyVersion(
            String tenantId, String idempotencyKey, Short idempotencyKeyVersion);

    Optional<Finding> findByIdAndTenantId(Long id, String tenantId);

    List<Finding> findTop200ByTenantIdAndProjectIdAndStateInOrderByPriorityScoreDesc(
            String tenantId, Long projectId, List<Finding.State> states);

    /**
     * The undeclared-key-fork alarm.
     *
     * <p>Two live findings from one agent on one (subject, category) means the
     * key composition changed without anybody declaring it, and every existing
     * finding has silently forked into a duplicate that looks like a new
     * problem. Cheap to check, and invisible until somebody does.
     */
    List<Finding> findByTenantIdAndAgentNameAndSubjectIdAndCategoryAndStateIn(
            String tenantId, String agentName, String subjectId, String category,
            List<Finding.State> states);

    long countByTenantIdAndProjectIdAndState(String tenantId, Long projectId, Finding.State state);
}
