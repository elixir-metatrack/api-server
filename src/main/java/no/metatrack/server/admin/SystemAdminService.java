package no.metatrack.server.admin;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import no.metatrack.server.auth.keycloak.KeycloakAdminClient;
import no.metatrack.server.file.File;
import no.metatrack.server.project.SystemAdminProjectService;
import no.metatrack.server.upload.UploadBatch;
import no.metatrack.server.upload.UploadBatchEntryStatus;
import no.metatrack.server.upload.UploadBatchStatus;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.RestResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class SystemAdminService {
    private static final int UPLOAD_ISSUE_LIMIT = 100;
    private final EntityManager entityManager;
    private final KeycloakAdminClient keycloakAdminClient;
    private final S3Client s3Client;
    private final SystemAdminProjectService projectService;
    private final String realm;
    private final String bucketName;
    private final Duration stalledUploadThreshold;

    @Inject
    public SystemAdminService(EntityManager entityManager,
            @RestClient KeycloakAdminClient keycloakAdminClient,
            S3Client s3Client,
            SystemAdminProjectService projectService,
            @ConfigProperty(name = "keycloak.realm") String realm,
            @ConfigProperty(name = "metatrack.s3.bucket-name") String bucketName,
            @ConfigProperty(name = "metatrack.admin.stalled-upload-threshold", defaultValue = "PT2H")
                    Duration stalledUploadThreshold) {
        if (stalledUploadThreshold.isZero() || stalledUploadThreshold.isNegative()) {
            throw new IllegalArgumentException("Stalled upload threshold must be positive");
        }
        this.entityManager = entityManager;
        this.keycloakAdminClient = keycloakAdminClient;
        this.s3Client = s3Client;
        this.projectService = projectService;
        this.realm = realm;
        this.bucketName = bucketName;
        this.stalledUploadThreshold = stalledUploadThreshold;
    }

    public AdminOverviewResponse overview() {
        Long users = null;
        String keycloakStatus = "unavailable";
        try (RestResponse<Integer> response = keycloakAdminClient.countUsers(realm)) {
            if (response.getStatus() == 200 && response.getEntity() != null) {
                users = response.getEntity().longValue();
                keycloakStatus = "available";
            }
        } catch (RuntimeException ignored) {
            // Keep the dashboard available when an integration is not.
        }

        String objectStorageStatus = "unavailable";
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
            objectStorageStatus = "available";
        } catch (RuntimeException ignored) {
            // Do not expose credentials, endpoint details, or raw upstream errors.
        }

        Long projects = null;
        Long samples = null;
        Long assays = null;
        Long files = null;
        Long ownerRisks = null;
        Long failed = null;
        Long stalled = null;
        List<AdminUploadIssueResponse> issues = List.of();
        String databaseStatus = "unavailable";
        try {
            entityManager.createNativeQuery("select 1").getSingleResult();
            projects = entityManager.createQuery("select count(p) from Project p", Long.class).getSingleResult();
            samples = entityManager.createQuery("select count(s) from Sample s", Long.class).getSingleResult();
            assays = entityManager.createQuery("select count(a) from Assay a", Long.class).getSingleResult();
            files = entityManager.createQuery("select count(f) from File f", Long.class).getSingleResult();
            ownerRisks = projectService.ownershipRiskCount();
            Instant cutoff = Instant.now().minus(stalledUploadThreshold);
            failed = countFailedBatches();
            stalled = countStalledBatches(cutoff);
            issues = uploadIssues(cutoff);
            databaseStatus = "available";
        } catch (RuntimeException ignored) {
            // Database metrics are null when unavailable rather than misleading zeroes.
            projects = null;
            samples = null;
            assays = null;
            files = null;
            ownerRisks = null;
            failed = null;
            stalled = null;
            issues = List.of();
        }

        return new AdminOverviewResponse(users, projects, samples, assays, files, ownerRisks, failed, stalled,
                issues, new AdminIntegrationStatus(databaseStatus, keycloakStatus, objectStorageStatus));
    }

    private long countFailedBatches() {
        return entityManager.createQuery("select count(b) from UploadBatch b where b.status in :statuses", Long.class)
                .setParameter("statuses", List.of(UploadBatchStatus.FAILED, UploadBatchStatus.PARTIAL_SUCCESS))
                .getSingleResult();
    }

    private long countStalledBatches(Instant cutoff) {
        return entityManager.createQuery("""
                        select count(b) from UploadBatch b
                        where b.status in :statuses and b.updatedAt < :cutoff
                        """, Long.class)
                .setParameter("statuses", List.of(UploadBatchStatus.OPEN, UploadBatchStatus.PROVISIONING,
                        UploadBatchStatus.DRAINING, UploadBatchStatus.PROCESSING))
                .setParameter("cutoff", cutoff)
                .getSingleResult();
    }

    private List<AdminUploadIssueResponse> uploadIssues(Instant cutoff) {
        List<UploadBatch> batches = entityManager.createQuery("""
                        select b from UploadBatch b
                        where b.status in :failed
                           or (b.status in :active and b.updatedAt < :cutoff)
                        order by b.updatedAt asc
                        """, UploadBatch.class)
                .setParameter("failed", List.of(UploadBatchStatus.FAILED, UploadBatchStatus.PARTIAL_SUCCESS))
                .setParameter("active", List.of(UploadBatchStatus.OPEN, UploadBatchStatus.PROVISIONING,
                        UploadBatchStatus.DRAINING, UploadBatchStatus.PROCESSING))
                .setParameter("cutoff", cutoff)
                .setMaxResults(UPLOAD_ISSUE_LIMIT)
                .getResultList();
        return batches.stream().map(batch -> new AdminUploadIssueResponse(
                batch.id,
                batch.requestedProject.id,
                batch.requestedProject.name,
                batch.status,
                batch.updatedAt,
                entityManager.createQuery("""
                                select count(e) from UploadBatchEntry e
                                where e.batch.id = :batch and e.status = :failed
                                """, Long.class)
                        .setParameter("batch", batch.id)
                        .setParameter("failed", UploadBatchEntryStatus.FAILED)
                        .getSingleResult())).toList();
    }
}