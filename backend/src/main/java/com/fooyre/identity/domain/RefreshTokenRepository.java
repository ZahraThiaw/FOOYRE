package com.fooyre.identity.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Refresh tokens (hachés). Aucune suppression : les jetons sont remplacés ou révoqués. */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Jeton présenté, verrouillé ({@code SELECT … FOR UPDATE}) : une seule rotation à la fois. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t WHERE t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(String tokenHash);

    /** Session ({@code sid}) toujours ouverte : au moins un jeton de la famille non révoqué. */
    @Query("""
            SELECT count(t) > 0 FROM RefreshToken t
            WHERE t.familyId = :familyId AND t.userId = :userId AND t.revokedAt IS NULL""")
    boolean isSessionOpen(UUID familyId, UUID userId);

    /** Révoque tous les jetons non révoqués d'une famille (session). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE RefreshToken t SET t.revokedAt = :now, t.revocationReason = :reason
            WHERE t.familyId = :familyId AND t.revokedAt IS NULL""")
    int revokeFamily(UUID familyId, RevocationReason reason, Instant now);
}
