package com.bxtralabs.pod.processor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaErrorHandlingConfig {

    // When a listener throws (e.g. the DB is briefly unavailable), Spring Kafka's default is to
    // redeliver 9 more times with no delay and then skip the message -- a few milliseconds of
    // outage is enough to drop it. Back off instead (1s, 2s, 4s ... capped at 15s, giving up
    // after ~1 minute). A message skipped anyway is recovered by StuckWorkSweeper.
    // Applied to every @KafkaListener via Spring Boot's default container factory.
    @Bean
    public CommonErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(1000, 2.0);
        backOff.setMaxInterval(15_000);
        backOff.setMaxElapsedTime(60_000);
        return new DefaultErrorHandler(backOff);
    }
}
