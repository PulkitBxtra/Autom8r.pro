package com.bxtralabs.pod.backend.repository;

import com.bxtralabs.pod.backend.model.RevokedToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RevokedTokenRepository extends JpaRepository<RevokedToken, String> {
}
