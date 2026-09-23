package com.intertec.autoops.core.repo;

import com.intertec.autoops.core.domain.Project;
import com.intertec.autoops.core.domain.ProjectStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    /** Tenant isolation: every by-id lookup is scoped to the caller's tenant. */
    Optional<Project> findByIdAndTenantId(Long id, String tenantId);

    /** Quota basis: only ACTIVE projects count toward MAX_PROJECTS. */
    long countByTenantIdAndStatus(String tenantId, ProjectStatus status);

    boolean existsByTenantIdAndNameAndStatus(String tenantId, String name, ProjectStatus status);

    /**
     * The provider's catalog sandbox, looked up by name.
     *
     * <p>By name because the sandbox has no id anyone stores — it is created on
     * first use and found again on every later test. Scoped to the tenant and
     * to ACTIVE so a deleted sandbox is recreated rather than resurrected.
     */
    Optional<Project> findByTenantIdAndNameAndStatus(String tenantId, String name,
                                                     ProjectStatus status);
}
