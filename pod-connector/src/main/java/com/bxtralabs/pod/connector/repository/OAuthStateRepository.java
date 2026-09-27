package com.bxtralabs.pod.connector.repository;

import com.bxtralabs.pod.connector.model.OAuthState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OAuthStateRepository extends JpaRepository<OAuthState, String> {

    // Abandoned sign-ins (the user closed the popup) never come back; sweep them away.
    @Modifying
    @Transactional
    @Query("delete from OAuthState s where s.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") long cutoff);

    // Consumes a state exactly once: whoever deletes the row owns the callback. A replayed or
    // concurrent second callback deletes nothing and is rejected.
    @Modifying
    @Transactional
    @Query("delete from OAuthState s where s.id = :id")
    int consume(@Param("id") String id);
}
