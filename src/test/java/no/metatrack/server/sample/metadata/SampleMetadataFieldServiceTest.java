package no.metatrack.server.sample.metadata;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SampleMetadataFieldServiceTest {
    @Test
    void normalizesValidKeys() {
        assertEquals("priority_level", SampleMetadataFieldService.validateKey(" Priority_Level "));
    }

    @Test
    void rejectsInvalidAndReservedKeys() {
        assertThrows(BadRequestException.class, () -> SampleMetadataFieldService.validateKey("1priority"));
        assertThrows(BadRequestException.class, () -> SampleMetadataFieldService.validateKey("collection_date"));
        assertThrows(BadRequestException.class, () -> SampleMetadataFieldService.validateKey("has space"));
        for (String key : new String[]{"project_accession", "study_accession", "sample_accession", "secondary_accession"}) {
            assertThrows(BadRequestException.class, () -> SampleMetadataFieldService.validateKey(key));
        }
    }

    @Test
    void trimsLabelsAndRejectsBlankLabels() {
        assertEquals("Priority", SampleMetadataFieldService.validateLabel(" Priority "));
        assertThrows(BadRequestException.class, () -> SampleMetadataFieldService.validateLabel("  "));
    }

    @Test
    void validatesOptionalTooltipAndPreservesPlainText() {
        assertNull(SampleMetadataFieldService.validateTooltip(null));
        assertNull(SampleMetadataFieldService.validateTooltip(""));
        assertNull(SampleMetadataFieldService.validateTooltip(" \n\t"));
        String text = " <example>\nUse this guidance. ";
        assertEquals(text, SampleMetadataFieldService.validateTooltip(text));
        assertEquals("a".repeat(2000), SampleMetadataFieldService.validateTooltip("a".repeat(2000)));
        assertThrows(BadRequestException.class,
                () -> SampleMetadataFieldService.validateTooltip("a".repeat(2001)));
    }

    @Test
    void patchPreservesReplacesAndClearsTooltip() throws Exception {
        SampleMetadataField field = new SampleMetadataField();
        field.tooltip = "Original";
        SampleMetadataFieldService service = new SampleMetadataFieldService() {
            @Override
            SampleMetadataField find(Long projectId, UUID fieldId) {
                return field;
            }
        };
        ObjectMapper mapper = new ObjectMapper();
        for (String json : new String[]{"{}", "{\"tooltip\":null}"}) {
            service.patch(1L, field.id, mapper.readValue(json, PatchSampleMetadataFieldRequest.class));
            assertEquals("Original", field.tooltip);
        }
        service.patch(1L, field.id, new PatchSampleMetadataFieldRequest(null, null, "Updated"));
        assertEquals("Updated", field.tooltip);
        assertEquals("Updated", SampleMetadataFieldResponse.fromEntity(field).tooltip());
        assertThrows(BadRequestException.class, () -> service.patch(1L, field.id,
                new PatchSampleMetadataFieldRequest(null, null, "a".repeat(2001))));
        assertEquals("Updated", field.tooltip);
        for (String blank : new String[]{"", " \n\t"}) {
            field.tooltip = "Original";
            service.patch(1L, field.id, new PatchSampleMetadataFieldRequest(null, null, blank));
            assertNull(field.tooltip);
            assertNull(SampleMetadataFieldResponse.fromEntity(field).tooltip());
        }
    }

    @Test
    void createPayloadAcceptsOptionalTooltip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CreateSampleMetadataFieldRequest legacy = mapper.readValue(
                "{\"key\":\"priority\",\"label\":\"Priority\",\"type\":\"TEXT\"}",
                CreateSampleMetadataFieldRequest.class);
        assertNull(legacy.tooltip());
        CreateSampleMetadataFieldRequest request = mapper.readValue(
                "{\"key\":\"priority\",\"label\":\"Priority\",\"type\":\"TEXT\",\"tooltip\":\"Help\"}",
                CreateSampleMetadataFieldRequest.class);
        assertEquals("Help", request.tooltip());
    }
}