package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.config.StepTopics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StepResultsTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final Orchestrator orchestrator = mock(Orchestrator.class);
    // Runs fallbacks on the calling thread so tests can verify them without waiting.
    private final StepResultPublisher publisher = new StepResultPublisher(kafka, orchestrator, jsonMapper,
            new DirectExecutorService());
    private final StepResultConsumer consumer = new StepResultConsumer(orchestrator, jsonMapper);

    private static final Map<String, Object> INPUT = Map.of("url", "http://x", "body", Map.of("n", 42));
    private static final Map<String, Object> OUTPUT = Map.of("status", 201, "body",
            Map.of("items", List.of(Map.of("id", 1), Map.of("id", 2)), "ok", true, "price", 19.5));

    // ---------- publisher ----------

    @Test
    void publishesResultKeyedByRun() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publish(new StepResultMessage("exn_1", "stp_a", INPUT, OUTPUT, null, false, 1));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq(StepTopics.STEP_RESULTS), eq("exn_1"), payload.capture());
        assertEquals(new StepResultMessage("exn_1", "stp_a", INPUT, OUTPUT, null, false, 1),
                jsonMapper.readValue(payload.getValue(), StepResultMessage.class));
        verifyNoInteractions(orchestrator);
    }

    @Test
    void failedSendFallsBackToRecordingDirectly() {
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("RecordTooLargeException")));

        publisher.publish(new StepResultMessage("exn_1", "stp_a", INPUT, OUTPUT, null, false, 1));

        verify(orchestrator).completeStep("exn_1", "stp_a", INPUT, OUTPUT, null, false, 1, false);
    }

    @Test
    void doesNotWaitForTheAck() {
        CompletableFuture<SendResult<String, String>> pending = new CompletableFuture<>();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(pending);

        long start = System.nanoTime();
        publisher.publish(new StepResultMessage("exn_1", "stp_a", null, Map.of(), null, false, 1));
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(tookMs < 100, "publish returned in " + tookMs + "ms without waiting for the ack");
        verifyNoInteractions(orchestrator); // not failed (yet), so no fallback
    }

    @Test
    void sendThatFailsLaterStillFallsBack() {
        CompletableFuture<SendResult<String, String>> pending = new CompletableFuture<>();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(pending);
        publisher.publish(new StepResultMessage("exn_1", "stp_a", null, null, "HTTP 503", true, 1));
        verifyNoInteractions(orchestrator);

        pending.completeExceptionally(new RuntimeException("delivery timeout"));

        verify(orchestrator).completeStep("exn_1", "stp_a", null, null, "HTTP 503", true, 1, false);
    }

    @Test
    void successfulSendDoesNotFallBack() {
        CompletableFuture<SendResult<String, String>> pending = new CompletableFuture<>();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(pending);
        publisher.publish(new StepResultMessage("exn_1", "stp_a", null, Map.of(), null, false, 1));

        pending.complete(mock(SendResult.class));

        verifyNoInteractions(orchestrator);
    }

    @Test
    void sendThrowingImmediatelyFallsBack() {
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("producer closed"));

        publisher.publish(new StepResultMessage("exn_1", "stp_a", null, Map.of("x", 1), null, false, 1));

        verify(orchestrator).completeStep("exn_1", "stp_a", null, Map.of("x", 1), null, false, 1, false);
    }

    // ---------- consumer ----------

    @Test
    void consumerAppliesSuccessThenAcks() {
        Acknowledgment ack = mock(Acknowledgment.class);
        String payload = jsonMapper.writeValueAsString(new StepResultMessage("exn_1", "stp_a", INPUT, OUTPUT, null, false, 1));

        consumer.consume(payload, ack);

        var inOrder = inOrder(orchestrator, ack);
        inOrder.verify(orchestrator).completeStep("exn_1", "stp_a", INPUT, OUTPUT, null, false, 1, false);
        inOrder.verify(ack).acknowledge();
    }

    @Test
    void consumerAppliesFailure() {
        Acknowledgment ack = mock(Acknowledgment.class);
        String payload = jsonMapper.writeValueAsString(new StepResultMessage("exn_1", "stp_a", INPUT, null, "HTTP 503", true, 1));

        consumer.consume(payload, ack);

        verify(orchestrator).completeStep("exn_1", "stp_a", INPUT, null, "HTTP 503", true, 1, false);
    }

    @Test
    void consumerDoesNotAckWhenApplyingFails() {
        Acknowledgment ack = mock(Acknowledgment.class);
        doThrow(new RuntimeException("db down")).when(orchestrator).completeStep(any(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyBoolean());
        String payload = jsonMapper.writeValueAsString(new StepResultMessage("exn_1", "stp_a", null, Map.of(), null, false, 1));

        assertThrows(RuntimeException.class, () -> consumer.consume(payload, ack));
        verify(ack, never()).acknowledge(); // Kafka redelivers
    }

    // Minimal same-thread ExecutorService for deterministic tests.
    private static class DirectExecutorService extends java.util.concurrent.AbstractExecutorService {
        private boolean shutdown;
        public void execute(Runnable command) { command.run(); }
        public void shutdown() { shutdown = true; }
        public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        public boolean isShutdown() { return shutdown; }
        public boolean isTerminated() { return shutdown; }
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) { return true; }
    }
}
