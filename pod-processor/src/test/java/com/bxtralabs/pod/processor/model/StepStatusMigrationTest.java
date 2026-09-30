package com.bxtralabs.pod.processor.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

// step_run.status has a CHECK constraint listing the statuses, and the schema comes from
// pod-backend's Flyway migrations. A status added to StepStatus without a migration that
// updates step_run_status_check would be refused by the database at run time; this fails first.
class StepStatusMigrationTest {

    private static final Path MIGRATIONS = Path.of("../pod-backend/src/main/resources/db/migration");

    @Test
    void theDatabaseAcceptsEveryStepStatus() throws IOException {
        assertTrue(Files.isDirectory(MIGRATIONS), "pod-backend's migrations should be at " + MIGRATIONS.toAbsolutePath());
        List<Path> files;
        try (Stream<Path> s = Files.list(MIGRATIONS)) {
            files = s.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted(Comparator.comparingInt(StepStatusMigrationTest::version))
                    .toList();
        }
        String latest = null;
        for (Path f : files) {
            String sql = Files.readString(f);
            if (sql.contains("step_run_status_check")) {
                latest = sql.substring(sql.lastIndexOf("step_run_status_check"));
            }
        }
        assertNotNull(latest, "no migration defines step_run_status_check");
        for (StepStatus status : StepStatus.values()) {
            assertTrue(latest.contains("'" + status.name() + "'"), "step_run_status_check doesn't allow " + status
                    + ": add a migration in pod-backend that recreates it with the new status");
        }
    }

    private static int version(Path p) {
        String name = p.getFileName().toString();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }
}
