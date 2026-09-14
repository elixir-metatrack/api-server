package no.metatrack.server.assay;

import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.sample.Sample;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class AssayServiceTest {
    @Test
    void rejectsSampleOutsideProjectBeforeLoadingAssays() {
        Long projectId = 1L;
        UUID sampleId = UUID.randomUUID();
        AssayService service = new AssayService();

        try (MockedStatic<Sample> sample = mockStatic(Sample.class)) {
            sample.when(() -> Sample.sampleExistsInProject(sampleId, projectId)).thenReturn(false);

            assertThrows(NotFoundException.class, () -> service.getAllAssaysInSample(projectId, sampleId));

            sample.verify(() -> Sample.getAllAssaysInSample(projectId, sampleId), never());
        }
    }
}