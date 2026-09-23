package com.intertec.autoops.agent.repo;

import com.intertec.autoops.agent.domain.Agent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AgentRepository extends JpaRepository<Agent, Long> {

    /**
     * How many delivered copies each catalog item currently has, in one query.
     *
     * <p>Counts LIVE rows rather than rollout events, so revoking a delivery
     * takes the number back down. A counter stored on the catalog item could
     * only ever go up, and would keep claiming a customer holds an agent that
     * was removed from their workspace.
     *
     * <p>{@code sourceId IS NOT NULL} excludes anything not delivered from a
     * catalog item.
     */
    @Query("SELECT a.sourceId, COUNT(a) FROM Agent a WHERE a.sourceId IS NOT NULL "
            + "GROUP BY a.sourceId")
    List<Object[]> countGroupedBySourceId();

    List<Agent> findByProjectIdAndTenantIdOrderByCreatedAtDesc(Long projectId, String tenantId);

    /** Tenant isolation: every by-id lookup is scoped to the caller's tenant. */
    Optional<Agent> findByIdAndTenantId(Long id, String tenantId);

    /** Quota basis: agents share MAX_AUTOMATIONS with workflows. */
    long countByTenantId(String tenantId);

    boolean existsByProjectIdAndName(Long projectId, String name);

    /**
     * Rollout de-dupe: a project holds at most one delivered copy of a given
     * catalog item. Scoped to the project rather than the tenant on purpose —
     * delivering the same agent into two of a customer's projects is a
     * legitimate rollout, two copies in ONE project is not.
     *
     * <p>Not covered by {@link #existsByProjectIdAndName}: that compares NAMES,
     * so renaming the catalog item and rolling out again slips a second copy of
     * the same source past it.
     */
    boolean existsByProjectIdAndSourceId(Long projectId, Long sourceId);

    /**
     * Every live copy of one catalog item, across every customer.
     *
     * <p>The read behind the provider's revoke screen. Counting deliveries has
     * been possible since rollout existed; NAMING them has not, which is why
     * an agent could be rolled out from the console and only taken back with
     * SQL — {@code AgentClient.revoke} has existed the whole time and had no
     * caller.
     *
     * <p>Deliberately not tenant-scoped: it answers a provider's question
     * ("who holds this?") and is reachable only from the internal surface,
     * which core-service gates on the PROVIDER role.
     */
    List<Agent> findBySourceIdOrderByTenantIdAscIdAsc(Long sourceId);
}
