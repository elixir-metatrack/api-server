package no.metatrack.server.sample.metadata;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import no.metatrack.server.sample.Sample;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "sample_metadata_value", uniqueConstraints = @UniqueConstraint(columnNames = {"sample_id", "field_id"}))
public class SampleMetadataValue extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    public Sample sample;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    public SampleMetadataField field;

    @Column(columnDefinition = "text")
    public String textValue;

    public BigDecimal numberValue;

    public Boolean booleanValue;

    public LocalDate dateValue;
}