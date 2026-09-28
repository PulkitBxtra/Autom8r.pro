package com.bxtralabs.pod.connector.triggers;

import com.bxtralabs.pod.connector.internal.InternalAuth;
import com.bxtralabs.pod.connector.security.UserAuth;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class TriggerController {

    private final TriggerService triggers;
    private final InternalAuth internalAuth;
    private final UserAuth userAuth;

    public TriggerController(TriggerService triggers, InternalAuth internalAuth, UserAuth userAuth) {
        this.triggers = triggers;
        this.internalAuth = internalAuth;
        this.userAuth = userAuth;
    }

    public record SubscribeRequest(@NotBlank String userId, @NotBlank String appId, @NotBlank String triggerId,
                                   String connectionId, Map<String, Object> config) {
    }

    // pod-backend: the workflow was turned on, or saved again while on.
    @PutMapping("/internal/triggers/{workflowId}")
    public ResponseEntity<TriggerService.Status> subscribe(@RequestHeader(value = InternalAuth.HEADER, required = false) String token,
                                                           @PathVariable String workflowId,
                                                           @Valid @RequestBody SubscribeRequest r) {
        internalAuth.require(token);
        TriggerService.Status status = triggers.subscribe(workflowId, r.userId(), r.appId(), r.triggerId(), r.connectionId(), r.config());
        return status == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(status);
    }

    // pod-backend: the workflow was turned off.
    @DeleteMapping("/internal/triggers/{workflowId}")
    public ResponseEntity<Void> unsubscribe(@RequestHeader(value = InternalAuth.HEADER, required = false) String token,
                                            @PathVariable String workflowId) {
        internalAuth.require(token);
        triggers.unsubscribe(workflowId);
        return ResponseEntity.noContent().build();
    }

    // The UI: is the workflow's trigger listening, and if not, why.
    @GetMapping("/triggers/{workflowId}")
    public ResponseEntity<TriggerService.Status> status(@RequestHeader("Authorization") String authorization,
                                                        @PathVariable String workflowId) {
        TriggerService.Status status = triggers.statusFor(workflowId, userAuth.requireUserId(authorization));
        return status == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(status);
    }

    // GitHub's webhook deliveries. Public: authenticated by the signature, not a login.
    @PostMapping("/hooks/github/{subscriptionId}")
    public ResponseEntity<Map<String, String>> github(@PathVariable String subscriptionId,
                                                      @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
                                                      @RequestHeader(value = "X-GitHub-Event", required = false) String event,
                                                      @RequestHeader(value = "X-GitHub-Delivery", required = false) String delivery,
                                                      @RequestBody byte[] body) {
        try {
            TriggerService.Outcome outcome = triggers.onGitHubDelivery(subscriptionId, signature, event, delivery, body);
            return ResponseEntity.ok(Map.of("result", outcome.name().toLowerCase()));
        } catch (TriggerService.InvalidDeliveryException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
        }
    }
}
