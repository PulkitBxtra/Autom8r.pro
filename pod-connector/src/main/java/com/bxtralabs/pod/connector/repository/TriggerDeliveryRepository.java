package com.bxtralabs.pod.connector.repository;

import com.bxtralabs.pod.connector.model.TriggerDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TriggerDeliveryRepository extends JpaRepository<TriggerDelivery, String> {

    // Records a delivery; 0 if it was already recorded (a redelivery).
    @Modifying
    @Transactional
    @Query(value = "insert into trigger_delivery (id, subscription_id, received_at) values (:id, :sub, :at) on conflict (id) do nothing",
            nativeQuery = true)
    int recordNew(@Param("id") String id, @Param("sub") String subscriptionId, @Param("at") long receivedAt);

    @Modifying
    @Transactional
    @Query("delete from TriggerDelivery d where d.receivedAt < :before")
    int deleteOlderThan(@Param("before") long before);
}
