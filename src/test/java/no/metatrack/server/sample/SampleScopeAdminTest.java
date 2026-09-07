package no.metatrack.server.sample;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.ws.rs.ForbiddenException;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectRoleCheck;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class SampleScopeAdminTest {
    @Test
    void unauthorizedLinkDoesNotEvenLookUpRequestedSampleIds() {
        Project root = new Project();
        root.id = 1L;
        Project sub = new Project();
        sub.id = 2L;
        sub.parentProject = root;
        SampleService service = new SampleService();
        service.projectRoleCheck = mock(ProjectRoleCheck.class);

        UUID sampleId = UUID.randomUUID();
        try (MockedStatic<PanacheEntityBase> persistence = mockStatic(PanacheEntityBase.class)) {
            persistence.when(() -> Project.findByIdOptional(sub.id)).thenReturn(Optional.of(sub));
            assertThrows(ForbiddenException.class, () -> service.linkSamples(sub.id, List.of(sampleId)));
            persistence.verify(() -> Sample.findByIdOptional(sampleId), never());
        }
    }
}
