package com.bxtralabs.pod.backend.repository;

import com.bxtralabs.pod.backend.model.WorkflowVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, String> {

    Optional<WorkflowVersion> findFirstByWorkflowIdOrderByVersionDesc(String workflowId);
}
