package no.metatrack.server.upload;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "upload_batch_transfer_admission", indexes = {
        @Index(name = "idx_upload_batch_transfer_admission_admitted_at", columnList = "admitted_at")
})
public class UploadBatchTransferAdmission extends PanacheEntityBase {
    @Id
    public UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false, unique = true)
    public UploadBatchEntry entry;

    @Column(name = "admitted_at", nullable = false)
    public Instant admittedAt;
}