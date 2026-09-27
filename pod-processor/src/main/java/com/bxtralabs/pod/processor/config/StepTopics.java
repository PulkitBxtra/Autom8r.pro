package com.bxtralabs.pod.processor.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

// Kafka topics for step execution. Created at startup if missing (and grown if the configured
// partition count goes up); replication is left to the broker's default.
@Configuration
public class StepTopics {

    // One message per READY step. StepTaskPublisher assigns partitions round-robin so a run's
    // parallel branches spread evenly. Each partition is consumed one message at a time, so the partition
    // count is the maximum number of steps running at once across ALL runs. The current
    // Aiven plan allows at most 2 partitions per topic; raise steps.topic-partitions (and
    // steps.worker-concurrency) together on a plan that allows more. Kafka can add
    // partitions to an existing topic but never remove them.
    public static final String STEP_TASKS = "step-tasks";

    // One message per finished step, keyed by runId so a run's results reach the orchestrator
    // in order on one partition. Applying a result is quick DB bookkeeping, so this topic
    // doesn't need as many partitions as step-tasks.
    public static final String STEP_RESULTS = "step-results";

    @Bean
    public NewTopic stepTasksTopic(@Value("${steps.topic-partitions:2}") int partitions) {
        return TopicBuilder.name(STEP_TASKS).partitions(partitions).build();
    }

    @Bean
    public NewTopic stepResultsTopic(@Value("${steps.results-partitions:2}") int partitions) {
        return TopicBuilder.name(STEP_RESULTS).partitions(partitions).build();
    }
}
