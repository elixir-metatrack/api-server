package no.metatrack.server.project;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.security.Authenticated;
import jakarta.validation.Validation;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectPublicationTest {
    private final ProjectPublicationController controller = new ProjectPublicationController();
    private final PublicationRequest request = new PublicationRequest("Publication", List.of("https://example.org"), List.of("pid:1"), "10.1/example");

    ProjectPublicationTest() {
        controller.projectRoleCheck = mock(ProjectRoleCheck.class);
        controller.publicationService = mock(ProjectPublicationService.class);
    }

    @Test
    void allEndpointsRequireAuthenticationAndProjectAccess() {
        assertNotNull(ProjectPublicationController.class.getAnnotation(Authenticated.class));
        assertThrows(ForbiddenException.class, () -> controller.list(1L));
        assertThrows(ForbiddenException.class, () -> controller.get(1L, 2L));
        assertThrows(ForbiddenException.class, () -> controller.create(1L, request));
        assertThrows(ForbiddenException.class, () -> controller.replace(1L, 2L, request));
        assertThrows(ForbiddenException.class, () -> controller.delete(1L, 2L));
        verifyNoInteractions(controller.publicationService);
    }

    @Test
    void viewersCanReadButCannotWrite() {
        when(controller.projectRoleCheck.isAtLeast(1L, ProjectRole.VIEWER)).thenReturn(true);
        PublicationResponse response = PublicationResponse.from(publication());
        when(controller.publicationService.list(1L)).thenReturn(List.of(response));
        when(controller.publicationService.get(1L, 2L)).thenReturn(response);
        assertEquals(List.of(response), controller.list(1L));
        assertEquals(response, controller.get(1L, 2L));
        assertThrows(ForbiddenException.class, () -> controller.create(1L, request));
        assertThrows(ForbiddenException.class, () -> controller.replace(1L, 2L, request));
        assertThrows(ForbiddenException.class, () -> controller.delete(1L, 2L));
        verify(controller.publicationService).list(1L);
        verify(controller.publicationService).get(1L, 2L);
        verifyNoMoreInteractions(controller.publicationService);
    }

    @Test
    void ownersCanWriteWithScopedIdsAndCreatedLocation() {
        when(controller.projectRoleCheck.isAtLeast(1L, ProjectRole.OWNER)).thenReturn(true);
        PublicationResponse saved = PublicationResponse.from(publication());
        when(controller.publicationService.create(1L, request)).thenReturn(saved);
        when(controller.publicationService.replace(1L, 2L, request)).thenReturn(saved);
        try (var response = controller.create(1L, request)) {
            assertEquals(201, response.getStatus());
            assertEquals("/api/projects/1/publications/2", response.getLocation().toString());
            assertEquals(saved, response.getEntity());
        }
        assertEquals(saved, controller.replace(1L, 2L, request));
        try (var response = controller.delete(1L, 2L)) {
            assertEquals(204, response.getStatus());
        }
        verify(controller.publicationService).create(1L, request);
        verify(controller.publicationService).replace(1L, 2L, request);
        verify(controller.publicationService).delete(1L, 2L);
    }

    @Test
    void requiredTitleAndOptionalMetadataAreValidated() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(request).isEmpty());
            assertTrue(validator.validate(new PublicationRequest("Title", null, null, null)).isEmpty());
            for (String title : new String[]{null, "", "  ", "a".repeat(256)}) {
                assertFalse(validator.validate(new PublicationRequest(title, null, null, null)).isEmpty());
            }
            assertFalse(validator.validate(new PublicationRequest("Title", List.of(""), null, null)).isEmpty());
            assertFalse(validator.validate(new PublicationRequest("Title", null, List.of(" "), null)).isEmpty());
            assertFalse(validator.validate(new PublicationRequest("Title", null, null, "a".repeat(256))).isEmpty());
        }
    }

    @Test
    void responseCopiesOrderedListsWithoutExposingEntity() {
        ProjectPublication publication = publication();
        publication.links.addAll(List.of("second", "first", "second"));
        publication.pids.add("pid:1");
        PublicationResponse response = PublicationResponse.from(publication);
        publication.links.clear();
        assertEquals(List.of("second", "first", "second"), response.links());
        assertEquals(List.of("pid:1"), response.pids());
        assertEquals(1L, response.projectId());
    }

    @Test
    void publicationLookupUsesBothProjectAndPublicationIds() {
        try (var persistence = mockStatic(PanacheEntityBase.class)) {
            PanacheQuery<ProjectPublication> query = mock(PanacheQuery.class);
            persistence.when(() -> ProjectPublication.find("id = ?1 and project.id = ?2", 2L, 1L)).thenReturn(query);
            when(query.firstResultOptional()).thenReturn(Optional.of(publication()));
            assertEquals(2L, ProjectPublication.findInProject(1L, 2L).orElseThrow().id);
        }
    }

    @Test
    void missingOrOtherProjectPublicationsAreNotFoundForEveryItemOperation() {
        var service = new ProjectPublicationService();
        try (var publications = mockStatic(ProjectPublication.class)) {
            publications.when(() -> ProjectPublication.findInProject(9L, 2L)).thenReturn(Optional.empty());
            assertThrows(NotFoundException.class, () -> service.get(9L, 2L));
            assertThrows(NotFoundException.class, () -> service.replace(9L, 2L, request));
            assertThrows(NotFoundException.class, () -> service.delete(9L, 2L));
        }
    }

    @Test
    void replacementClearsOmittedMetadataAndUpdatesProjectTimestamp() {
        var service = new ProjectPublicationService();
        ProjectPublication publication = publication();
        publication.links.add("old link");
        publication.pids.add("old pid");
        publication.doi = "old doi";
        publication.project.modifiedOn = Instant.EPOCH;
        try (var publications = mockStatic(ProjectPublication.class)) {
            publications.when(() -> ProjectPublication.findInProject(1L, 2L)).thenReturn(Optional.of(publication));
            PublicationResponse response = service.replace(1L, 2L, new PublicationRequest("New title", null, null, null));
            assertEquals("New title", response.title());
            assertTrue(response.links().isEmpty());
            assertTrue(response.pids().isEmpty());
            assertNull(response.doi());
            assertTrue(publication.project.modifiedOn.isAfter(Instant.EPOCH));
            service.replace(1L, 2L, request);
            assertEquals(request.links(), publication.links);
            assertEquals(request.pids(), publication.pids);
            assertEquals(request.doi(), publication.doi);
        }
    }

    @Test
    void deletionUpdatesProjectTimestamp() {
        var service = new ProjectPublicationService();
        ProjectPublication publication = spy(publication());
        doNothing().when(publication).delete();
        publication.project.modifiedOn = Instant.EPOCH;
        try (var publications = mockStatic(ProjectPublication.class)) {
            publications.when(() -> ProjectPublication.findInProject(1L, 2L)).thenReturn(Optional.of(publication));
            service.delete(1L, 2L);
            verify(publication).delete();
            assertTrue(publication.project.modifiedOn.isAfter(Instant.EPOCH));
        }
    }

    private ProjectPublication publication() {
        ProjectPublication publication = new ProjectPublication();
        publication.id = 2L;
        publication.project = new Project();
        publication.project.id = 1L;
        publication.title = "Publication";
        publication.links = new ArrayList<>();
        publication.pids = new ArrayList<>();
        return publication;
    }
}