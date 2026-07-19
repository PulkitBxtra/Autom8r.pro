package com.bxtralabs.pod.webhooks.repository;

import com.bxtralabs.pod.webhooks.model.App;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppRepository extends JpaRepository<App, String> {

    boolean existsByName(String name);
}
