package no.metatrack.server.sample.metadata;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SampleMetadataServiceTest {
    private final SampleMetadataService service = new SampleMetadataService();

    @Test
    void normalizesSupportedTypes() {
        assertEquals("text", service.normalize(SampleMetadataFieldType.TEXT, "text"));
        assertEquals(new BigDecimal("3.5"), service.normalize(SampleMetadataFieldType.NUMBER, 3.5));
        assertEquals(true, service.normalize(SampleMetadataFieldType.BOOLEAN, true));
        assertEquals(LocalDate.of(2026, 7, 13), service.normalize(SampleMetadataFieldType.DATE, "2026-07-13"));
    }

    @Test
    void rejectsWrongTypesAndMalformedValues() {
        assertThrows(BadRequestException.class, () -> service.normalize(SampleMetadataFieldType.TEXT, 1));
        assertThrows(BadRequestException.class, () -> service.normalize(SampleMetadataFieldType.BOOLEAN, "true"));
        assertThrows(BadRequestException.class, () -> service.normalize(SampleMetadataFieldType.DATE, "13/07/2026"));
        assertThrows(BadRequestException.class, () -> service.normalize(SampleMetadataFieldType.NUMBER, Double.NaN));
    }

    @Test
    void parsesCsvValuesByDefinitionType() {
        assertEquals(new BigDecimal("12.50"), service.parseCsvValue(SampleMetadataFieldType.NUMBER, "12.50"));
        assertEquals(false, service.parseCsvValue(SampleMetadataFieldType.BOOLEAN, "false"));
        assertEquals(LocalDate.of(2026, 7, 13), service.parseCsvValue(SampleMetadataFieldType.DATE, "2026-07-13"));
        assertEquals(null, service.parseCsvValue(SampleMetadataFieldType.TEXT, " "));
        assertThrows(BadRequestException.class,
                () -> service.parseCsvValue(SampleMetadataFieldType.BOOLEAN, "yes"));
    }
}