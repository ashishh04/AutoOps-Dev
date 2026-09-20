package com.intertec.autoops.workflow.repo;

import com.intertec.autoops.workflow.domain.Workflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface WorkflowRepository extends JpaRepository<Workflow, Long> {

    /** Provider console: workflow counts for every tenant, in one query. */
    @Query("SELECT w.tenantId, COUNT(w) FROM Workflow w GROUP BY w.tenantId")
    List<Object[]> countGroupedByTenant();

    /**
     * How many delivered copies each catalog item currently has, in one query.
     *
     * <p>Counts LIVE rows rather than rollout events, so revoking a delivery
     * takes the number back down — a stored counter on the catalog item could
     * only ever go up, and would keep claiming a customer holds something they
     * no longer do.
     *
     * <p>{@code source_id IS NOT NULL} excludes tenant-authored workflows,
     * which came from no catalog item.
     */
    @Query("SELECT w.sourceId, COUNT(w) FROM Workflow w WHERE w.sourceId IS NOT NULL "
            + "GROUP BY w.sourceId")
    List<Object[]> countGroupedBySourceId();

    List<Workflow> findByProjectIdAndTenantIdOrderByCreatedAtDesc(Long projectId, String tenantId);

    /** Tenant isolation: every by-id lookup is scoped to the caller's tenant. */
    Optional<Workflow> findByIdAndTenantId(Long id, String tenantId);

    /** Quota basis: workflows share MAX_AUTOMATIONS with agents. */
    long countByTenantId(String tenantId);

    boolean existsByProjectIdAndName(Long projectId, String name);

    /** Name uniqueness within one delivery kind — see {@code doCreate}. */
    boolean existsByProjectIdAndNameAndDelivery(Long projectId, String name,
                                                Workflow.Delivery delivery);

    /**
     * Rollout de-dupe: a project holds at most one delivered copy of a given
     * catalog item. Scoped to the project rather than the tenant on purpose —
     * delivering the same workflow into two of a customer's projects is a
     * legitimate rollout, two copies in ONE project is not.
     *
     * <p>Not covered by {@link #existsByProjectIdAndName}: that compares NAMES,
     * so renaming the catalog item and rolling out again slips a second copy of
     * the same source past it.
     */
    boolean existsByProjectIdAndSourceId(Long projectId, Long sourceId);

    /**
     * Whether this project already holds this catalog item delivered THIS WAY.
     *
     * <p>Narrower than {@link #existsByProjectIdAndSourceId} on purpose: a
     * sealed agent component and a separately licensed copy of the same
     * catalog workflow are two legitimate rows, and only a repeat of the same
     * KIND is a duplicate.
     */
    boolean existsByProjectIdAndSourceIdAndDelivery(Long projectId, Long sourceId,
                                                    Workflow.Delivery delivery);

    /** The tenant-visible listing: everything except sealed agent components. */
    List<Workflow> findByProjectIdAndTenantIdAndDeliveryOrderByCreatedAtDesc(
            Long projectId, String tenantId, Workflow.Delivery delivery);
}
