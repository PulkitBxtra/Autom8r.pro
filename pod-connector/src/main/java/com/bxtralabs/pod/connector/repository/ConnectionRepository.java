package com.bxtralabs.pod.connector.repository;

import com.bxtralabs.pod.connector.model.Connection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConnectionRepository extends JpaRepository<Connection, String> {

    // The Connections page: newest first.
    List<Connection> findByUserIdOrderByCreatedAtDesc(String userId);

    // A step's connection picker: only the connections for that step's app.
    List<Connection> findByUserIdAndAppIdOrderByCreatedAtDesc(String userId, String appId);

    // SELECT ... FOR UPDATE: anyone about to refresh a connection's token takes this first, so
    // two refreshes of the same connection never overlap (with rotating refresh tokens the
    // second would use an already-invalidated refresh token and break the connection).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Connection c where c.id = :id")
    Optional<Connection> findByIdForUpdate(@Param("id") String id);

    // Active OAuth connections whose access token expires before the cutoff and aren't backing
    // off, soonest first. SKIP LOCKED: several pod-connector instances (or a request refreshing
    // on demand right now) each take different rows instead of waiting or double-refreshing.
    // Tokens that live shorter than the window would otherwise be "expiring soon" right after
    // every refresh and get refreshed on every run forever. So a connection refreshed recently
    // (after recentCutoff) is left alone unless it's actually about to expire (before
    // urgentCutoff), and never twice in the same run (runStartedAt).
    @Query(value = "select * from connection where auth_type = 'OAUTH' and status = 'ACTIVE' " +
            "and expires_at is not null and expires_at < :cutoff " +
            "and (next_refresh_at is null or next_refresh_at <= :now) " +
            "and (last_refreshed_at is null or last_refreshed_at < :runStartedAt) " +
            "and (last_refreshed_at is null or last_refreshed_at < :recentCutoff or expires_at < :urgentCutoff) " +
            "order by expires_at limit :limit for update skip locked", nativeQuery = true)
    List<Connection> lockDueForRefresh(@Param("cutoff") long cutoff, @Param("now") long now,
                                       @Param("runStartedAt") long runStartedAt,
                                       @Param("recentCutoff") long recentCutoff,
                                       @Param("urgentCutoff") long urgentCutoff,
                                       @Param("limit") int limit);
}
