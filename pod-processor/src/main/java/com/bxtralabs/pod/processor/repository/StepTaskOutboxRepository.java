package com.bxtralabs.pod.processor.repository;

import com.bxtralabs.pod.processor.model.StepTaskOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StepTaskOutboxRepository extends JpaRepository<StepTaskOutbox, String> {

    // Oldest rows first, locked until the caller's transaction ends. SKIP LOCKED means several
    // publisher instances (or pods) each take a different batch instead of double-publishing.
    @Query(value = "select * from step_task_outbox order by created_at, id limit :limit for update skip locked",
            nativeQuery = true)
    List<StepTaskOutbox> lockBatch(@Param("limit") int limit);
}
