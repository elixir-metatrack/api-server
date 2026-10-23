package no.metatrack.server.project;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class ProjectPublicationService {
    @Transactional
    public List<PublicationResponse> list(Long projectId) {
        return ProjectPublication.<ProjectPublication>list("project.id = ?1 order by id", projectId).stream()
                .map(PublicationResponse::from).toList();
    }

    @Transactional
    public PublicationResponse get(Long projectId, Long publicationId) {
        return PublicationResponse.from(requirePublication(projectId, publicationId));
    }

    @Transactional
    public PublicationResponse create(Long projectId, PublicationRequest request) {
        Project project = Project.findById(projectId);
        if (project == null) throw new NotFoundException();

        ProjectPublication publication = new ProjectPublication();
        publication.project = project;
        replaceFields(publication, request);
        publication.persist();
        project.modifiedOn = Instant.now();
        return PublicationResponse.from(publication);
    }

    @Transactional
    public PublicationResponse replace(Long projectId, Long publicationId, PublicationRequest request) {
        ProjectPublication publication = requirePublication(projectId, publicationId);
        replaceFields(publication, request);
        publication.project.modifiedOn = Instant.now();
        return PublicationResponse.from(publication);
    }

    @Transactional
    public void delete(Long projectId, Long publicationId) {
        ProjectPublication publication = requirePublication(projectId, publicationId);
        publication.project.modifiedOn = Instant.now();
        publication.delete();
    }

    private ProjectPublication requirePublication(Long projectId, Long publicationId) {
        return ProjectPublication.findInProject(projectId, publicationId).orElseThrow(NotFoundException::new);
    }

    private void replaceFields(ProjectPublication publication, PublicationRequest request) {
        publication.title = request.title();
        publication.doi = request.doi();
        publication.links.clear();
        if (request.links() != null) publication.links.addAll(request.links());
        publication.pids.clear();
        if (request.pids() != null) publication.pids.addAll(request.pids());
    }
}