package com.bxtralabs.pod.connector.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

// An app event we've already turned into a run, by the app's delivery id (e.g. GitHub's
// X-GitHub-Delivery). Apps redeliver on timeouts and from their UI; inserting the id first makes
// a second delivery of the same event start nothing. Old rows are pruned.
@Entity
@Table(indexes = @Index(name = "idx_trigger_delivery_received", columnList = "receivedAt"))
public class TriggerDelivery {

    @Id
    private String id;
    private String subscriptionId;
    private Long receivedAt;

    public TriggerDelivery() {
    }

    public TriggerDelivery(String id, String subscriptionId, long receivedAt) {
        this.id = id;
        this.subscriptionId = subscriptionId;
        this.receivedAt = receivedAt;
    }

    public String getId() {
        return id;
    }
}
