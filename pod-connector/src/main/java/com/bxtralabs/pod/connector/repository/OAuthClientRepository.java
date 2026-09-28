package com.bxtralabs.pod.connector.repository;

import com.bxtralabs.pod.connector.model.OAuthClient;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OAuthClientRepository extends JpaRepository<OAuthClient, String> {

    List<OAuthClient> findByUserIdOrderByCreatedAtDesc(String userId);

    // The OAuth app picker in "Connect with GitHub": only that provider's apps.
    List<OAuthClient> findByUserIdAndProviderOrderByCreatedAtDesc(String userId, String provider);
}
