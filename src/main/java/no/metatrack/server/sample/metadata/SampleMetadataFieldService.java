package no.metatrack.server.sample.metadata;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.project.Project;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class SampleMetadataFieldService {
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Set<String> RESERVED_KEYS = Set.of(
            "id", "name", "sample_name", "alias", "tax_id", "host_tax_id", "mlst", "isolation_source",
            "collection_date", "location", "geographic_location", "sequencing_lab", "institution",
            "host_health_state", "project_title", "description", "isolate", "collected_by", "latitude",
            "longitude", "environmental_sample", "host_associated", "host_common_name", "host_subject_id",
            "collector_name", "collecting_institution", "host_sex", "influenza_test_method",
            "influenza_test_result", "other_pathogens_tested", "other_pathogens_test_result", "host_habitat",
            "isolation_source_host_associated", "host_behaviour", "isolation_source_non_host_associated",
            "influenza_virus_type", "influenza_sub_type", "serovar", "strain", "host_age", "county",
            "commune", "hospital_health_institution", "created_on", "modified_on", "custom_metadata");

    public List<SampleMetadataField> list(Long projectId, boolean includeArchived) {
        requireProject(projectId);
        if (includeArchived) {
            return SampleMetadataField.list("project.id = ?1 order by key", projectId);
        }
        return SampleMetadataField.list("project.id = ?1 and archivedOn is null order by key", projectId);
    }

    @Transactional
    public SampleMetadataField create(Long projectId, CreateSampleMetadataFieldRequest request) {
        Project project = requireProject(projectId);
        String key = validateKey(request.key());
        String label = validateLabel(request.label());
        if (SampleMetadataField.count("project.id = ?1 and key = ?2", projectId, key) > 0) {
            throw new BadRequestException("A metadata field with key '" + key + "' already exists in this project");
        }

        Instant now = Instant.now();
        SampleMetadataField field = new SampleMetadataField();
        field.project = project;
        field.key = key;
        field.label = label;
        field.type = request.type();
        field.createdOn = now;
        field.modifiedOn = now;
        field.persist();
        return field;
    }

    @Transactional
    public SampleMetadataField patch(Long projectId, UUID fieldId, PatchSampleMetadataFieldRequest request) {
        SampleMetadataField field = find(projectId, fieldId);
        if (request.label() != null) field.label = validateLabel(request.label());
        if (request.archived() != null) {
            field.archivedOn = request.archived() ? Instant.now() : null;
        }
        field.modifiedOn = Instant.now();
        return field;
    }

    @Transactional
    public void archive(Long projectId, UUID fieldId) {
        SampleMetadataField field = find(projectId, fieldId);
        if (field.archivedOn == null) {
            field.archivedOn = Instant.now();
            field.modifiedOn = field.archivedOn;
        }
    }

    SampleMetadataField find(Long projectId, UUID fieldId) {
        return SampleMetadataField.<SampleMetadataField>find("id = ?1 and project.id = ?2", fieldId, projectId)
                .firstResultOptional()
                .orElseThrow(NotFoundException::new);
    }

    static String validateKey(String rawKey) {
        if (rawKey == null) throw new BadRequestException("Metadata field key is required");
        String key = rawKey.trim().toLowerCase(Locale.ROOT);
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new BadRequestException("Metadata field key must start with a letter and contain only lowercase letters, numbers, and underscores");
        }
        if (RESERVED_KEYS.contains(key)) throw new BadRequestException("Metadata field key '" + key + "' is reserved");
        return key;
    }

    static String validateLabel(String rawLabel) {
        if (rawLabel == null || rawLabel.isBlank()) throw new BadRequestException("Metadata field label is required");
        String label = rawLabel.trim();
        if (label.length() > 255) throw new BadRequestException("Metadata field label must not exceed 255 characters");
        return label;
    }

    private Project requireProject(Long projectId) {
        return Project.<Project>findByIdOptional(projectId).orElseThrow(() -> new NotFoundException("Project not found"));
    }
}