package com.bxtralabs.pod.backend.repository;

import com.bxtralabs.pod.backend.model.ExecutionRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExecutionRunRepository extends JpaRepository<ExecutionRun, String> {

    // Newest first; served by the (workflow_id, start_timestamp) index on execution_run.
    List<ExecutionRun> findByWorkflowIdOrderByStartTimestampDesc(String workflowId, Pageable page);
}
