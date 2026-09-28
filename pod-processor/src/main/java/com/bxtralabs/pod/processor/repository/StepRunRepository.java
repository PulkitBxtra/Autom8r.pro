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
    @Query("update StepRun s set s.status = :running, s.startedAt = :now, s.attempt = s.attempt + 1, " +
            "s.firstStartedAt = coalesce(s.firstStartedAt, :now) " +
            "where s.id = :id and s.status = :ready")
    int claim(@Param("id") String id, @Param("now") long now,
              @Param("ready") StepStatus ready, @Param("running") StepStatus running);

    default int claim(String id, long now) {
        return claim(id, now, StepStatus.READY, StepStatus.RUNNING);
    }

    // Retries whose wait is over, oldest first, locked until the caller's transaction ends.
    // SKIP LOCKED lets several schedulers (or pods) run without picking the same step.
    @Query(value = "select * from step_run where status = 'RETRY_WAIT' and next_attempt_at <= :now " +
            "order by next_attempt_at limit :limit for update skip locked", nativeQuery = true)
    List<StepRun> lockDueRetries(@Param("now") long now, @Param("limit") int limit);

    // RUNNING steps claimed before the cutoff: their worker probably died or their result was
    // lost. Not locked -- the sweeper hands each to Orchestrator.completeStep, which takes the
    // run lock and re-checks status and attempt, so a result that races in wins or loses cleanly.
    @Query(value = "select * from step_run where status = 'RUNNING' and started_at < :cutoff " +
            "order by started_at limit :limit", nativeQuery = true)
    List<StepRun> findStuckRunning(@Param("cutoff") long cutoff, @Param("limit") int limit);

    // READY steps that have waited since before the cutoff (or predate ready_at), locked so
    // concurrent sweepers don't re-queue the same step twice in one pass.
    @Query(value = "select * from step_run where status = 'READY' and (ready_at is null or ready_at < :cutoff) " +
            "order by ready_at nulls first limit :limit for update skip locked", nativeQuery = true)
    List<StepRun> lockStaleReady(@Param("cutoff") long cutoff, @Param("limit") int limit);
}
