package com.intertec.autoops.agent.repo;

import com.intertec.autoops.agent.domain.FindingObservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FindingObservationRepository extends JpaRepository<FindingObservation, Long> {

    /** Replay protection: has this exact verdict already been ingested? */
    boolean existsByTenantIdAndVerdictId(String tenantId, String verdictId);

    List<FindingObservation> findTop100ByFindingIdOrderByObservedAtDesc(Long findingId);
}
