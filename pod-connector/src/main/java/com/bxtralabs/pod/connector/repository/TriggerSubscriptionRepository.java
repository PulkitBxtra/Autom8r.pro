package com.bxtralabs.pod.connector.repository;

import com.bxtralabs.pod.connector.model.TriggerSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TriggerSubscriptionRepository extends JpaRepository<TriggerSubscription, String> {

    Optional<TriggerSubscription> findByWorkflowId(String workflowId);
}
