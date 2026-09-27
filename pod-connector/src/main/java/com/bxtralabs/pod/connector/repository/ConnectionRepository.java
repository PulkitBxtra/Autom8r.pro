package com.bxtralabs.pod.connector.repository;

import com.bxtralabs.pod.connector.model.Connection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConnectionRepository extends JpaRepository<Connection, String> {

    // The Connections page: newest first.
    List<Connection> findByUserIdOrderByCreatedAtDesc(String userId);

    // A step's connection picker: only the connections for that step's app.
    List<Connection> findByUserIdAndAppIdOrderByCreatedAtDesc(String userId, String appId);
}
