package com.bxtralabs.pod.webhooks.repository;


import com.bxtralabs.pod.webhooks.model.ExecutionRun;
import com.bxtralabs.pod.webhooks.model.ExecutionRunOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ExecutionRunOutboxRepository extends JpaRepository<ExecutionRunOutbox, String> {

}
