package com.bxtralabs.pod.connector.triggers;

import com.bxtralabs.pod.connector.model.TriggerSubscription;

import java.util.Map;

// Registers an app trigger with the app (and removes it), using the trigger's connection.
public interface AppTriggerRegistrar {

    boolean supports(String appId);

    // What the app made: its id for the registration (to remove it later; null if nothing was
    // registered), and the secret it signs deliveries with when it picks its own (Stripe), else
    // null for the one we handed it.
    record Registration(String externalId, String secret) {
    }

    // Asks the app to call hookUrl when the trigger's event happens, signing deliveries with
    // secret (if the app takes one).
    Registration register(TriggerSubscription subscription, Map<String, String> credentials, String hookUrl, String secret)
            throws TriggerSetupException;

    // Best effort: the workflow is being turned off or changed.
    void unregister(TriggerSubscription subscription, Map<String, String> credentials);
}
