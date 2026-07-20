package com.acme.gateway.persistence;

import com.acme.gateway.domain.model.CustomerProfile;
import com.acme.gateway.domain.model.ProfileStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * The ONLY query shapes the MCP surface can reach. Every parameter is a bind
 * variable; there is no dynamic JPQL/SQL construction anywhere in this service.
 */
public interface ProfileRepository extends JpaRepository<CustomerProfile, UUID> {

    @Query("""
           SELECT p FROM CustomerProfile p
           WHERE (:status IS NULL OR p.status = :status)
             AND (:name   IS NULL OR LOWER(p.displayName) LIKE LOWER(CONCAT('%', :name, '%')))
             AND (:after  IS NULL OR p.createdAt >= :after)
             AND (:before IS NULL OR p.createdAt <= :before)
           ORDER BY p.createdAt DESC
           """)
    Page<CustomerProfile> search(@Param("status") ProfileStatus status,
                                 @Param("name") String nameContains,
                                 @Param("after") Instant createdAfter,
                                 @Param("before") Instant createdBefore,
                                 Pageable pageable);
}
