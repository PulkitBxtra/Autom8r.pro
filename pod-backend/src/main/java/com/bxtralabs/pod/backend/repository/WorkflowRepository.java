package com.bxtralabs.pod.backend.repository;

import com.bxtralabs.pod.backend.model.Workflow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowRepository extends JpaRepository<Workflow, String> {

    List<Workflow> findByUserId(String userId);
}
