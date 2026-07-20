package com.acme.gateway.domain.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customer_profile")
public class CustomerProfile {

    @Id
    @Column(columnDefinition = "uniqueidentifier")
    private UUID id;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(nullable = false, length = 256)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProfileStatus status;

    /** Optimistic lock: protects against concurrent agent writes. */
    @Version
    @Column(nullable = false)
    private long version;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CustomerProfile() { }

    public CustomerProfile(String displayName, String email, ProfileStatus status) {
        this.id = UUID.randomUUID();
        this.displayName = displayName;
        this.email = email;
        this.status = status;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void transitionTo(ProfileStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getEmail() { return email; }
    public ProfileStatus getStatus() { return status; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
