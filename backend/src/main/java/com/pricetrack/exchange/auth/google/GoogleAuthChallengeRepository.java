package com.pricetrack.exchange.auth.google;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface GoogleAuthChallengeRepository extends JpaRepository<GoogleAuthChallenge, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from GoogleAuthChallenge c where c.id = :id")
    Optional<GoogleAuthChallenge> findLocked(@Param("id") String id);
    long countByBindingHashAndCreatedAtAfter(String bindingHash, Instant after);
}
