package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.model.StepRun;
import com.bxtralabs.pod.processor.model.StepStatus;
import com.bxtralabs.pod.processor.model.StepTaskOutbox;
import com.bxtralabs.pod.processor.repository.StepRunRepository;
import com.bxtralabs.pod.processor.repository.StepTaskOutboxRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

// Puts RETRY_WAIT steps whose wait is over back on the step-tasks queue: READY plus an outbox
// row, in one transaction, exactly like a step that just became READY for the first time.
@Service
public class RetryScheduler {

    static final int BATCH_SIZE = 100;

    private final StepRunRepository stepRunRepository;
    private final StepTaskOutboxRepository outboxRepository;
    private final TransactionTemplate transactionTemplate;

    public RetryScheduler(StepRunRepository stepRunRepository, StepTaskOutboxRepository outboxRepository,
                          TransactionTemplate transactionTemplate) {
        this.stepRunRepository = stepRunRepository;
        this.outboxRepository = outboxRepository;
        this.transactionTemplate = transactionTemplate;
    }

    // Retry delays are seconds or more, so half a second of extra wait is noise.
    @Scheduled(fixedDelay = 500)
    public void releaseDueRetries() {
        int[] released = new int[1];
        do {
            released[0] = 0;
            transactionTemplate.executeWithoutResult(tx -> {
                List<StepRun> due = stepRunRepository.lockDueRetries(System.currentTimeMillis(), BATCH_SIZE);
                for (StepRun step : due) {
                    step.setStatus(StepStatus.READY);
                    step.setNextAttemptAt(null);
                }
                stepRunRepository.saveAll(due);
                outboxRepository.saveAll(due.stream().map(s -> new StepTaskOutbox(s.getRunId(), s.getId())).toList());
                released[0] = due.size();
            });
        } while (released[0] == BATCH_SIZE);
    }
}
