package no.metatrack.server.sample.metadata;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    }

    @Test
    void trimsLabelsAndRejectsBlankLabels() {
        assertEquals("Priority", SampleMetadataFieldService.validateLabel(" Priority "));
        assertThrows(BadRequestException.class, () -> SampleMetadataFieldService.validateLabel("  "));
    }
}