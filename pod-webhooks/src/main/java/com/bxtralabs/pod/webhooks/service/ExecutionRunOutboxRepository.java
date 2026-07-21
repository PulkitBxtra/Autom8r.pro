package com.bxtralabs.pod.webhooks.service;

import com.bxtralabs.pod.webhooks.model.ExecutionRunOutbox;
import org.springframework.data.repository.Repository;

interface ExecutionRunOutboxRepository extends Repository<ExecutionRunOutbox, String> {
}
