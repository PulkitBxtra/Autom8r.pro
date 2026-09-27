package com.bxtralabs.pod.backend.repository;

import com.bxtralabs.pod.backend.model.StepRunView;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StepRunViewRepository extends JpaRepository<StepRunView, String> {

    List<StepRunView> findByRunIdOrderByCreatedAtAsc(String runId);
}
