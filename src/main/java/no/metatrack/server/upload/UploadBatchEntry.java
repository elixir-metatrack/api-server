package no.metatrack.server.upload;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.file.File;
import no.metatrack.server.file.ReadRole;
import no.metatrack.server.sample.Sample;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "upload_batch_entry", indexes = {
        @Index(name = "idx_upload_batch_entry_batch_status", columnList = "batch_id, status, manifest_row"),
        @Index(name = "idx_upload_batch_entry_retry", columnList = "status, next_retry_at, processing_lease_until"),
        @Index(name = "idx_upload_batch_entry_sample_assay", columnList = "sample_id, assay_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_upload_batch_entry_row", columnNames = {"batch_id", "manifest_row"}),
        @UniqueConstraint(name = "uq_upload_batch_entry_path", columnNames = {"batch_id", "relative_path"}),
        @UniqueConstraint(name = "uq_upload_batch_entry_virtual_path",
                columnNames = {"batch_id", "destination_virtual_path"})
})
public class UploadBatchEntry extends PanacheEntityBase {
    @Id
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    public UploadBatch batch;

    @Column(nullable = false)
    public int manifestRow;

    @Column(nullable = false, length = 1024)
    public String relativePath;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    public Sample sample;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    public Assay assay;

    @Column(nullable = false)
    public String fileName;

    @Column(nullable = false, length = 1024)
    public String stagingKey;

    @Column(nullable = false)
    public String destinationVirtualPath;

    @Column(nullable = false, length = 1024, unique = true)
    public String destinationObjectKey;

    @Column(length = 1024)
    public String previousObjectKey;

    @Column(nullable = false)
    public long expectedSizeBytes;

    @Column(nullable = false, length = 64)
    public String expectedSha256;

    public String expectedMd5;

    public String expectedUnencryptedMd5;

    @Enumerated(EnumType.STRING)
    public ReadRole readRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    public UploadBatchEntryStatus status;

    @Column(nullable = false)
    public long bytesTransferred;

    @Column(nullable = false)
    public int attempts;

    public Instant nextRetryAt;

    public Instant processingLeaseUntil;

    public UUID processingLeaseToken;

    @Column(columnDefinition = "text")
    public String sourceEtag;

    @Column(columnDefinition = "text")
    public String sourceVersionId;

    @Column(columnDefinition = "text")
    public String multipartUploadId;

    public Long multipartPartSizeBytes;

    @Column(columnDefinition = "text")
    public String destinationEtag;

    @Column(columnDefinition = "text")
    public String destinationVersionId;

    @Column(columnDefinition = "text")
    public String lastError;

    public Instant promotedAt;

    public Instant stagingCleanupAfter;

    public Instant stagingCleanupCompletedAt;

    public Instant previousObjectCleanupAfter;

    public Instant previousObjectCleanupCompletedAt;

    public Instant destinationCleanupCompletedAt;

    @Column(nullable = false)
    public Instant createdAt;

    @Column(nullable = false)
    public Instant updatedAt;

    @OneToOne(mappedBy = "uploadBatchEntry", fetch = FetchType.LAZY)
    public File file;
}