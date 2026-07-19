package com.bxtralabs.pod.webhooks.repository;

import com.bxtralabs.pod.webhooks.model.ExecutionRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ExecutionRunRepository extends JpaRepository<ExecutionRun, String> {

}
