package com.bxtralabs.pod.connector.repository;

import com.bxtralabs.pod.connector.model.TriggerSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TriggerSubscriptionRepository extends JpaRepository<TriggerSubscription, String> {

    Optional<TriggerSubscription> findByWorkflowId(String workflowId);

    List<TriggerSubscription> findByAppIdAndRoutingKeyAndStatus(String appId, String routingKey, String status);

    List<TriggerSubscription> findByConnectionIdAndStatus(String connectionId, String status);
}
