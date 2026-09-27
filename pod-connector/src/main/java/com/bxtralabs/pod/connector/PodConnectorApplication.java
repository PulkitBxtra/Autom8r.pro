package com.bxtralabs.pod.connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// Runs TokenRefreshScheduler.
@EnableScheduling
public class PodConnectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PodConnectorApplication.class, args);
    }

}
