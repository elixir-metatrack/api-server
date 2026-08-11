package no.metatrack.server.sample.vocabulary;

import jakarta.ws.rs.ForbiddenException;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class SampleVocabularyControllerTest {
    @Test
    void permitsViewerReadsButRejectsViewerMutations() {
        Long projectId = 1L;
        SampleVocabularyController controller = new SampleVocabularyController();
        controller.projectRoleCheck = mock(ProjectRoleCheck.class);
        controller.vocabularyService = mock(SampleVocabularyManagementService.class);
        when(controller.projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)).thenReturn(true);
        when(controller.projectRoleCheck.isAtLeast(projectId, ProjectRole.ADMIN)).thenReturn(false);
        when(controller.vocabularyService.list(projectId)).thenReturn(List.of());

        controller.list(projectId);
        assertThrows(ForbiddenException.class,
                () -> controller.replace(projectId, "host_sex", new PutSampleVocabularyRequest(List.of("female"))));

        verify(controller.vocabularyService).list(projectId);
        verify(controller.vocabularyService, never()).replace(anyLong(), anyString(), any());
    }

    @Test
    void requiresAdminForDelete() {
        Long projectId = 1L;
        SampleVocabularyController controller = new SampleVocabularyController();
        controller.projectRoleCheck = mock(ProjectRoleCheck.class);
        controller.vocabularyService = mock(SampleVocabularyManagementService.class);

        assertThrows(ForbiddenException.class, () -> controller.delete(projectId, "host_sex"));
        verifyNoInteractions(controller.vocabularyService);
    }
}