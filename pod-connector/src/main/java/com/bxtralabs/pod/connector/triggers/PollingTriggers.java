package com.bxtralabs.pod.connector.triggers;

import com.bxtralabs.pod.connector.model.TriggerSubscription;

import java.util.List;
import java.util.Map;

// App triggers found by asking the app what's new (Gmail, Google Sheets: no webhooks to register),
// polled by TriggerPoller about once a minute. register() records where things stand now in the
// subscription's meta (a mailbox's history id, a sheet's row count), so what's already there
// doesn't start runs; poll() returns what's new since then and the meta to save once those runs
// have started. Nothing needs a public address.
public interface PollingTriggers extends AppTriggerRegistrar {

    // key: identifies the event across polls (deliveries are de-duplicated on it).
    record Event(String key, Map<String, Object> body) {
    }

    record Poll(List<Event> events, Map<String, Object> meta) {
    }

    Poll poll(TriggerSubscription subscription, Map<String, String> credentials) throws TriggerSetupException;

    @Override
    default void unregister(TriggerSubscription subscription, Map<String, String> credentials) {
        // Nothing was registered with the app.
    }
}
