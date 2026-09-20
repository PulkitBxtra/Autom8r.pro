package com.bxtralabs.pod.processor.repository;

import com.bxtralabs.pod.processor.model.ExecutionRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionRunRepository extends JpaRepository<ExecutionRun, String> {
}
