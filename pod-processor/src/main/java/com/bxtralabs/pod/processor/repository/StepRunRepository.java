package com.bxtralabs.pod.processor.repository;

import com.bxtralabs.pod.processor.model.StepRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StepRunRepository extends JpaRepository<StepRun, String> {

    List<StepRun> findByRunId(String runId);

    Optional<StepRun> findByRunIdAndNodeId(String runId, String nodeId);

    boolean existsByRunId(String runId);
}
