package com.bxtralabs.pod.workflow.service;

import com.bxtralabs.pod.workflow.model.ExecutionRunOutbox;
import com.bxtralabs.pod.workflow.repository.RunOutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;

@Service
public class kafkaProducer {
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    public kafkaProducer(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Autowired
    private RunOutboxRepository runOutboxRepository;

    public void sendMessage(String topic, String message) {
        kafkaTemplate.send(topic, message);
    }


    @Scheduled(fixedRate = 1000)
    public void publishEvents(){

        ArrayList<ExecutionRunOutbox> executionRunOutboxArrayList = new ArrayList<>();
        executionRunOutboxArrayList = runOutboxRepository.findFirst10ByOrderByIdAsc();;

        executionRunOutboxArrayList.forEach(x->{
            sendMessage("workflow-events", x.getExecutionId());
            runOutboxRepository.deleteById(x.getId());
        }
        );


    }


}
