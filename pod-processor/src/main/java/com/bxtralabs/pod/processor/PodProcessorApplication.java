package com.bxtralabs.pod.processor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

@SpringBootApplication
@EnableKafka
public class PodProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PodProcessorApplication.class, args);
    }

}
