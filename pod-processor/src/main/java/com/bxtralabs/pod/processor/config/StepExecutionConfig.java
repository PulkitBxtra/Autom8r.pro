package com.bxtralabs.pod.processor.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class StepExecutionConfig {

    // Threads that execute steps. Parallel branches of one run, and steps of different
    // runs, run concurrently up to this many at a time; the rest queue.
    @Bean(name = "stepPool")
    public ThreadPoolTaskExecutor stepPool(@Value("${steps.pool-size:8}") int poolSize) {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(poolSize);
        pool.setMaxPoolSize(poolSize);
        pool.setQueueCapacity(10_000);
        pool.setThreadNamePrefix("step-");
        // Finish in-flight steps on shutdown instead of abandoning them mid-run.
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(30);
        return pool;
    }
}
