package no.metatrack.server.admin;

import java.util.List;

public record AdminOverviewResponse(
        Long users,
        Long projects,
        Long samples,
        Long assays,
        Long files,
        Long projectsNeedingOwner,
        Long failedUploadBatches,
        Long stalledUploadBatches,
        List<AdminUploadIssueResponse> uploadIssues,
        AdminIntegrationStatus integrations) {
}