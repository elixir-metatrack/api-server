package no.metatrack.server.project;

import java.util.List;

public record PublicationResponse(Long id, Long projectId, String title, List<String> links, List<String> pids, String doi) {
    public static PublicationResponse from(ProjectPublication publication) {
        return new PublicationResponse(publication.id, publication.project.id, publication.title,
                List.copyOf(publication.links), List.copyOf(publication.pids), publication.doi);
    }
}