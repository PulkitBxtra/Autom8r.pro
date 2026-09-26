package com.bxtralabs.pod.processor.repository;

import com.bxtralabs.pod.processor.model.StepRun;
import com.bxtralabs.pod.processor.model.StepStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface StepRunRepository extends JpaRepository<StepRun, String> {

    List<StepRun> findByRunId(String runId);

    Optional<StepRun> findByRunIdAndNodeId(String runId, String nodeId);

    boolean existsByRunId(String runId);

    // READY -> RUNNING, only if it's still READY. Returns 0 if another worker already
    // claimed it or the run was cancelled, so each step executes at most once per attempt.
    @Modifying
    @Transactional
    @Query("update StepRun s set s.status = :running, s.startedAt = :now, s.attempt = s.attempt + 1 " +
            "where s.id = :id and s.status = :ready")
    int claim(@Param("id") String id, @Param("now") long now,
              @Param("ready") StepStatus ready, @Param("running") StepStatus running);

    default int claim(String id, long now) {
        return claim(id, now, StepStatus.READY, StepStatus.RUNNING);
    }
}
