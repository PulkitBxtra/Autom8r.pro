package com.bxtralabs.pod.workflow.repository;

import com.bxtralabs.pod.workflow.model.ExecutionRunOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RunOutboxRepository extends JpaRepository<ExecutionRunOutbox, String> {

    // Oldest rows first (ids are ULIDs, so they sort by creation time), locked until the
    // caller's transaction ends. SKIP LOCKED means several pod-workflow instances each take a
    // different batch instead of all publishing the same runs.
    @Query(value = "select * from execution_run_outbox order by id limit :limit for update skip locked",
            nativeQuery = true)
    List<ExecutionRunOutbox> lockBatch(@Param("limit") int limit);
}
