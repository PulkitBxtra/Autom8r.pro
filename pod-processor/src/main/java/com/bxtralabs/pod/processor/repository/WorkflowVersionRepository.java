package com.bxtralabs.pod.processor.repository;

import com.bxtralabs.pod.processor.model.WorkflowVersion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, String> {
}
