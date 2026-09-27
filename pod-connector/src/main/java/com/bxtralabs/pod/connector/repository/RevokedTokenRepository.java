package com.bxtralabs.pod.connector.repository;

import com.bxtralabs.pod.connector.security.RevokedTokenView;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RevokedTokenRepository extends JpaRepository<RevokedTokenView, String> {
}
