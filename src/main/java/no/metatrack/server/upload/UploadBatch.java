package no.metatrack.server.upload;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import no.metatrack.server.project.Project;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "upload_batch", indexes = {
        @Index(name = "idx_upload_batch_project_created", columnList = "requested_project_id, created_at"),
        @Index(name = "idx_upload_batch_uploader_created", columnList = "uploader_id, created_at"),
        @Index(name = "idx_upload_batch_status_expiry", columnList = "status, expires_at")
})
public class UploadBatch extends PanacheEntityBase {
    @Id
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    public Project requestedProject;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    public Project owningProject;

    @Column(nullable = false)
    public UUID uploaderId;

    @Column(nullable = false, unique = true, length = 128)
    public String sftpUsername;

    @Column(name = "sftp_public_key", nullable = false, columnDefinition = "text")
    public String sftpPublicKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    public UploadBatchStatus status;

    @Column(nullable = false)
    public Instant expiresAt;

    @Column(nullable = false)
    public long reservedBytes;

    @Column(nullable = false)
    public int provisioningAttempts;

    public Instant provisioningLeaseUntil;

    @Column(columnDefinition = "text")
    public String lastError;

    public Instant frozenAt;

    public Instant cleanupAfter;

    public Instant stagingCleanupCompletedAt;

    public Instant sftpUserRevokedAt;

    @Column(nullable = false)
    public int sftpUserRevocationAttempts;

    public Instant sftpUserRevocationNextAttemptAt;

    @Column(columnDefinition = "text")
    public String sftpUserRevocationLastError;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(nullable = false)
    public Instant updatedAt;

    @OneToMany(mappedBy = "batch", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<UploadBatchEntry> entries = new ArrayList<>();
}