package no.metatrack.server.stats;

import io.quarkus.security.Authenticated;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProjectStatisticsControllerTest {
    StatisticsService statisticsService;
    ProjectRoleCheck projectRoleCheck;
    ProjectStatisticsController controller;

    @BeforeEach
    void setUp() {
        statisticsService = mock(StatisticsService.class);
        projectRoleCheck = mock(ProjectRoleCheck.class);
        controller = new ProjectStatisticsController();
        controller.statisticsService = statisticsService;
        controller.projectRoleCheck = projectRoleCheck;
    }

    @Test
    void permitsViewerAndDelegates() {
        var expected = new StorageStatistics(2L, 30L);
        try (MockedStatic<Project> project = mockStatic(Project.class)) {
            project.when(() -> Project.projectExists(42L)).thenReturn(true);
            when(projectRoleCheck.isAtLeast(42L, ProjectRole.VIEWER)).thenReturn(true);
            when(statisticsService.getProjectStorageStatistics(42L)).thenReturn(expected);

            assertSame(expected, controller.getStorageStatistics(42L));
            verify(statisticsService).getProjectStorageStatistics(42L);
        }
    }

    @Test
    void rejectsCallerWithoutViewerAccess() {
        try (MockedStatic<Project> project = mockStatic(Project.class)) {
            project.when(() -> Project.projectExists(42L)).thenReturn(true);

            assertThrows(ForbiddenException.class, () -> controller.getStorageStatistics(42L));
            verify(projectRoleCheck).isAtLeast(42L, ProjectRole.VIEWER);
            verifyNoInteractions(statisticsService);
        }
    }

    @Test
    void reportsMissingProjectBeforeAuthorization() {
        try (MockedStatic<Project> project = mockStatic(Project.class)) {
            project.when(() -> Project.projectExists(42L)).thenReturn(false);

            assertThrows(NotFoundException.class, () -> controller.getStorageStatistics(42L));
            verifyNoInteractions(projectRoleCheck, statisticsService);
        }
    }

    @Test
    void requiresAuthentication() throws NoSuchMethodException {
        assertNotNull(ProjectStatisticsController.class
                .getMethod("getStorageStatistics", Long.class)
                .getAnnotation(Authenticated.class));
    }
}