package com.intertec.autoops.agent.repo;

import com.intertec.autoops.agent.domain.FindingTransition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FindingTransitionRepository extends JpaRepository<FindingTransition, Long> {

    List<FindingTransition> findTop100ByFindingIdOrderByAtDesc(Long findingId);
}
