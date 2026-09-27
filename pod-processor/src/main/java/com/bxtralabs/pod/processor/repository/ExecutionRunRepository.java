package com.bxtralabs.pod.processor.repository;

import com.bxtralabs.pod.processor.model.ExecutionRun;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExecutionRunRepository extends JpaRepository<ExecutionRun, String> {

    // SELECT ... FOR UPDATE. Every change to a run's steps takes this lock first, so step
    // completions within one run are applied one at a time and always see each other's writes.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ExecutionRun r where r.id = :id")
    Optional<ExecutionRun> findByIdForUpdate(@Param("id") String id);

    // Runs triggered before the cutoff that never started (no steps): their workflow-events
    // message was lost or given up on.
    @Query(value = "select r.id from execution_run r where r.status = 'PENDING' and r.start_timestamp < :cutoff " +
            "and not exists (select 1 from step_run s where s.run_id = r.id) " +
            "order by r.start_timestamp limit :limit", nativeQuery = true)
    List<String> findUnstartedRunIds(@Param("cutoff") long cutoff, @Param("limit") int limit);
}
