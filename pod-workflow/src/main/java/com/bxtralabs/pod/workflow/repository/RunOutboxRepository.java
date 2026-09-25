package com.bxtralabs.pod.workflow.repository;

import com.bxtralabs.pod.workflow.model.ExecutionRunOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.ArrayList;

public interface RunOutboxRepository extends JpaRepository<ExecutionRunOutbox, String> {

//    ArrayList<ExecutionRunOutbox> findTen();
    ArrayList<ExecutionRunOutbox> findFirst100ByOrderByIdAsc();


}
