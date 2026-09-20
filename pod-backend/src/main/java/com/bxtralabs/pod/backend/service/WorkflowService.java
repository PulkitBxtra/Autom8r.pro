package com.bxtralabs.pod.backend.service;

import com.bxtralabs.pod.backend.common.NotFoundException;
import com.bxtralabs.pod.backend.model.Workflow;
import com.bxtralabs.pod.backend.repository.WorkflowRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WorkflowService {

    @Autowired
    private WorkflowRepository workflowRepository;

    public List<Workflow> listForUser(String userId) {
        return workflowRepository.findByUserId(userId);
    }

    public Workflow getForUser(String id, String userId) {
        Workflow workflow = workflowRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Workflow not found: " + id));

        // Don't reveal that a workflow with this id exists if it belongs to someone else.
        if (!userId.equals(workflow.getUserId())) {
            throw new NotFoundException("Workflow not found: " + id);
        }
        return workflow;
    }
}
