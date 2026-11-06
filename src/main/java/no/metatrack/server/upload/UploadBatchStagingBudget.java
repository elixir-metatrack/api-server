package no.metatrack.server.upload;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "upload_batch_staging_budget")
public class UploadBatchStagingBudget extends PanacheEntityBase {
    @Id
    public Short id;

    @Column(nullable = false)
    public long reservedBytes;
}