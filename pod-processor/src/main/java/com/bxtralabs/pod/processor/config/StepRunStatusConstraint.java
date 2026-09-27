package com.bxtralabs.pod.processor.config;

import com.bxtralabs.pod.processor.model.StepStatus;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

// Hibernate creates a CHECK constraint listing StepStatus's values when it first creates
// step_run, but ddl-auto=update never changes it afterwards -- so a newly added status (like
// RETRY_WAIT) would be rejected by the database. This rebuilds the constraint from the enum
// on every startup, so adding a status needs no manual migration.
//
// Depends on the EntityManagerFactory so it runs after Hibernate's schema update, and it runs
// during startup, before any Kafka listener starts writing step rows. Replace with a real
// migration tool (Flyway/Liquibase) once there's more than this one fix.
@Component
public class StepRunStatusConstraint {

    private final JdbcTemplate jdbc;

    public StepRunStatusConstraint(JdbcTemplate jdbc, EntityManagerFactory schemaIsReady) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void sync() {
        // Rebuilding takes a brief exclusive lock on step_run and re-checks every row, so skip
        // it when the constraint already allows every status (the usual case).
        String current = jdbc.query(
                "select pg_get_constraintdef(oid) from pg_constraint " +
                        "where conname = 'step_run_status_check' and conrelid = 'step_run'::regclass",
                rs -> rs.next() ? rs.getString(1) : null);
        if (current != null && Arrays.stream(StepStatus.values()).allMatch(s -> current.contains("'" + s.name() + "'"))) {
            return;
        }

        String allowed = Arrays.stream(StepStatus.values())
                .map(s -> "'" + s.name() + "'")
                .collect(Collectors.joining(", "));
        System.out.println("Updating step_run_status_check to allow: " + allowed);
        // One statement so there's never a moment without the constraint.
        jdbc.execute("alter table step_run drop constraint if exists step_run_status_check, "
                + "add constraint step_run_status_check check (status in (" + allowed + "))");
    }
}
