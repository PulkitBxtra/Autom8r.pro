package com.bxtralabs.pod.connector.triggers;

import com.bxtralabs.pod.connector.model.TriggerSubscription;

import java.util.Map;

// Registers an app trigger with the app (and removes it), using the trigger's connection.
public interface AppTriggerRegistrar {

    boolean supports(String appId);

    // Asks the app to call hookUrl when the trigger's event happens, signing deliveries with
    // secret. Returns the app's id for the registration (to remove it later).
    String register(TriggerSubscription subscription, Map<String, String> credentials, String hookUrl, String secret)
            throws TriggerSetupException;

    // Best effort: the workflow is being turned off or changed.
    void unregister(TriggerSubscription subscription, Map<String, String> credentials);
}
