package com.bxtralabs.pod.webhooks.repository;

import com.bxtralabs.pod.webhooks.model.Workflow;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRepository extends JpaRepository<Workflow, Long> {

}
