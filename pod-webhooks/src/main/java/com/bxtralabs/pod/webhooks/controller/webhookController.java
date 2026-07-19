package com.bxtralabs.pod.webhooks.controller;

import com.bxtralabs.pod.webhooks.model.Workflow;
import com.bxtralabs.pod.webhooks.service.WorkflowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class webhookController {

    @Autowired
    WorkflowService workflowRepository;

    @PostMapping("/create/workflow")
    public String createWorkflow(@RequestBody Workflow workflow) {

        String workflowId = workflowRepository.createWorkflow(workflow);
        return workflowId;

    }


    @PostMapping("/execute/{workflowId}")
    public String execute(@PathVariable String workflowId) {

        // add logic to check token and extract UserId for Logging

        // Store it in DB a new trigger

        // push in on a queue(kafka/redis)




        return workflowId;
    }
}
