package com.intertec.autoops.core.repo;

import com.intertec.autoops.core.domain.RunArtifact;
import com.intertec.autoops.core.domain.RunStatus;
import com.intertec.autoops.core.domain.RunTargetType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RunArtifactRepository extends JpaRepository<RunArtifact, Long> {

    /** What a run produced, in the order the steps produced it. */
    List<RunArtifact> findByTenantIdAndRunIdAndPurgedAtIsNullOrderByIdAsc(
            String tenantId, Long runId);

    /** Tenant isolation: the download path never looks up by id alone. */
    Optional<RunArtifact> findByIdAndTenantId(Long id, String tenantId);

    /**
     * The same-named artifact from the most recent EARLIER successful run of
     * the same target — "yesterday's file".
     *
     * <p>This is the query the whole table was built for. A daily check writes
     * {@code snapshot.json}; tomorrow's run asks this for the previous one and
     * compares. Three constraints, each load-bearing:
     *
     * <ul>
     *   <li><b>{@code SUCCEEDED} only.</b> A failed run's snapshot is a partial
     *       collection, and diffing against a partial produces a report full of
     *       resources that were never actually deleted — the single worst
     *       failure mode available to a change report. Skipping to the last
     *       GOOD run instead means a day of downtime widens the comparison
     *       window rather than corrupting it.</li>
     *   <li><b>{@code id <} rather than a timestamp.</b> Same reason
     *       {@code RunRepository} orders by id: two runs can share a
     *       {@code createdAt} to the microsecond, and a re-run triggered by
     *       hand can land out of clock order entirely.</li>
     *   <li><b>{@code purgedAt is null}.</b> A row whose object retention has
     *       already deleted must read as "no previous artifact", not as a key
     *       that 404s at download time.</li>
     * </ul>
     *
     * <p>Returns a list so the caller can pass {@code PageRequest.of(0, 1)};
     * there is no derived-query spelling of this join.
     */
    @Query("""
            select a from RunArtifact a, Run r
            where a.runId = r.id
              and a.tenantId = :tenantId
              and a.filename = :filename
              and a.purgedAt is null
              and r.targetType = :targetType
              and r.targetId = :targetId
              and r.status = :succeeded
              and r.id < :beforeRunId
            order by r.id desc""")
    List<RunArtifact> findPreceding(@Param("tenantId") String tenantId,
                                    @Param("targetType") RunTargetType targetType,
                                    @Param("targetId") Long targetId,
                                    @Param("filename") String filename,
                                    @Param("succeeded") RunStatus succeeded,
                                    @Param("beforeRunId") Long beforeRunId,
                                    Pageable limit);

    /**
     * Live rows older than the cutoff — the retention sweeper's input.
     *
     * <p>Ordered oldest first so a backlog drains in a stable order rather than
     * re-reading the same page each sweep.
     */
    List<RunArtifact> findTop200ByPurgedAtIsNullAndCreatedAtLessThanOrderByCreatedAtAsc(
            Instant cutoff);
}
