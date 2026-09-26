package com.intertec.autoops.auth.repo;

import com.intertec.autoops.auth.domain.OtpEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface OtpRepository extends JpaRepository<OtpEntry, Long> {

    Optional<OtpEntry> findTopByEmailAndTenantIdAndConsumedAtIsNullOrderByCreatedAtDesc(
            String email, String tenantId);

    Optional<OtpEntry> findByProviderMessageId(String providerMessageId);

    /** Retention sweep: challenges whose expiry is older than the cutoff. */
    @Modifying
    @Query("delete from OtpEntry o where o.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);
}
