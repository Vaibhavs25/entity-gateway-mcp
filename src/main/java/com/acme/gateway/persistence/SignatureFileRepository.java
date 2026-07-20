package com.acme.gateway.persistence;

import com.acme.gateway.domain.model.SignatureFile;
import com.acme.gateway.domain.model.SignatureFileStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SignatureFileRepository extends JpaRepository<SignatureFile, UUID> {

    List<SignatureFile> findByProfileIdAndStatusInOrderByUploadedAtDesc(
            UUID profileId, Collection<SignatureFileStatus> statuses);

    long countByProfileIdAndStatus(UUID profileId, SignatureFileStatus status);
}
