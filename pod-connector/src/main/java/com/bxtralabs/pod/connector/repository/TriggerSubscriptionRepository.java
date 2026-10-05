package com.bxtralabs.pod.connector.repository;

import com.bxtralabs.pod.connector.model.TriggerSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TriggerSubscriptionRepository extends JpaRepository<TriggerSubscription, String> {

    Optional<TriggerSubscription> findByWorkflowId(String workflowId);

    List<TriggerSubscription> findByAppIdAndRoutingKeyAndStatus(String appId, String routingKey, String status);

    List<TriggerSubscription> findByConnectionIdAndStatus(String connectionId, String status);

    // Active subscriptions of polled apps, least recently polled first (updated_at moves on every
    // poll), locked for this transaction; ones another instance is polling right now are skipped.
    @Query(value = "select * from trigger_subscription where status = 'ACTIVE' and app_id in (:apps) "
            + "and updated_at < :before order by updated_at limit :limit for update skip locked", nativeQuery = true)
    List<TriggerSubscription> lockDueForPoll(@Param("apps") List<String> apps, @Param("before") long before,
                                             @Param("limit") int limit);
}
