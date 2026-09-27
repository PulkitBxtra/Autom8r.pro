package com.bxtralabs.pod.processor.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

// Kafka topics for step execution. Created at startup if missing; replication is left to
// the broker's default (hosted clusters usually require more than 1).
@Configuration
public class StepTopics {

    // One message per READY step. StepTaskPublisher assigns partitions round-robin so a run's
    // parallel branches spread evenly. Each partition is consumed one message at a time, so the partition
    // count is the maximum number of steps running at once across ALL runs. The current
    // Aiven plan allows at most 2 partitions per topic; raise steps.topic-partitions (and
    // steps.worker-concurrency) together on a plan that allows more. Kafka can add
    // partitions to an existing topic but never remove them.
    public static final String STEP_TASKS = "step-tasks";

    @Bean
    public NewTopic stepTasksTopic(@Value("${steps.topic-partitions:2}") int partitions) {
        return TopicBuilder.name(STEP_TASKS).partitions(partitions).build();
    }
}
