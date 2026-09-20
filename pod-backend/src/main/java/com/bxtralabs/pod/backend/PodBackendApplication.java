package com.bxtralabs.pod.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PodBackendApplication {

    // what to build
    // Auth endpoint
    //
    /*
        user ->  login, logout, signup, view
        workflow-> list, create, update, view particular
     */

    public static void main(String[] args) {
        SpringApplication.run(PodBackendApplication.class, args);
    }

}
