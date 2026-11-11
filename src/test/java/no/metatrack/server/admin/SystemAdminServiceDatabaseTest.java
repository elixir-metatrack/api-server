package no.metatrack.server.admin;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import no.metatrack.server.auth.keycloak.KeycloakAdminClient;
import no.metatrack.server.file.SubProjectDatabase;
import no.metatrack.server.file.SubProjectTestProfile;
import no.metatrack.server.project.SystemAdminProjectService;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
@QuarkusTestResource(value = SubProjectDatabase.class, restrictToAnnotatedClass = true)
@TestProfile(SubProjectTestProfile.class)
@TestTransaction
class SystemAdminServiceDatabaseTest {
    @Inject
    SystemAdminService adminService;

    @Inject
    SystemAdminProjectService projectService;

    @BeforeEach
    void mockIntegrations() {
        KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
        when(keycloak.countUsers("test")).thenReturn(RestResponse.ok(12));
        QuarkusMock.installMockForType(keycloak, KeycloakAdminClient.class, RestClient.LITERAL);

        S3Client s3Client = mock(S3Client.class);
        when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());
        QuarkusMock.installMockForType(s3Client, S3Client.class);
    }

    @Test
    void overviewQueriesUsageOwnershipAndUploadMetricsAndReportsIntegrationHealth() {
        AdminOverviewResponse overview = adminService.overview();

        assertEquals(12L, overview.users());
        assertNotNull(overview.projects());
        assertNotNull(overview.samples());
        assertNotNull(overview.assays());
        assertNotNull(overview.files());
        assertNotNull(overview.projectsNeedingOwner());
        assertEquals(0L, overview.failedUploadBatches());
        assertEquals(0L, overview.stalledUploadBatches());
        assertEquals("available", overview.integrations().database());
        assertEquals("available", overview.integrations().keycloak());
        assertEquals("available", overview.integrations().objectStorage());
    }

    @Test
    void projectOversightQueriesRunAgainstTheFlywaySchema() {
        assertEquals(0, projectService.listProjects(0, 10).size());
        assertEquals(0, projectService.ownershipRisks(0, 10).size());
        assertEquals(0L, projectService.ownershipRiskCount());
    }
}