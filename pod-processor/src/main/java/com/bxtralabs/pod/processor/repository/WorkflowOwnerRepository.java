package com.bxtralabs.pod.processor.repository;

import com.bxtralabs.pod.processor.model.WorkflowOwner;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowOwnerRepository extends JpaRepository<WorkflowOwner, String> {
}
