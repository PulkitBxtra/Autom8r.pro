package com.bxtralabs.pod.webhooks.service;

import com.bxtralabs.pod.webhooks.model.Workflow;
import com.bxtralabs.pod.webhooks.repository.WorkflowRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class WorkflowService {

    @Autowired
    private WorkflowRepository workflowRepository;

    public String createWorkflow(Workflow workflow) {

        Workflow w = workflowRepository.save(workflow);
        return w.getId();
    }
}
