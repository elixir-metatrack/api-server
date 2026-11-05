package no.metatrack.server.sample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicSampleControllerTest {
    @Test
    void serializesOnlyPublicMetadataWithYearRatherThanFullCollectionDate() throws Exception {
        PublicSampleResponse sample = new PublicSampleResponse(new UUID(0, 1), 42, "Bacillus subtilis", "Org",
                9606, "Homo sapiens", 2024, "Blood", "SAMPLE-001");

        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(sample));

        Set<String> fields = new HashSet<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("id", "taxId", "scientificName", "dataOwningOrganization", "hostTaxId",
                "hostScientificName", "collectionYear", "isolationSource", "sampleAccession"), fields);
        assertEquals(9606, json.get("hostTaxId").intValue());
        assertEquals("Homo sapiens", json.get("hostScientificName").textValue());
        assertTrue(json.get("collectionYear").isInt());
        assertEquals(2024, json.get("collectionYear").intValue());
        assertEquals("Blood", json.get("isolationSource").textValue());
        assertEquals("SAMPLE-001", json.get("sampleAccession").textValue());
        assertFalse(json.has("collectionDate"));
    }

    @Test
    void delegatesRequestedPageAndExplicitlyPermitsAnonymousAccess() throws Exception {
        PublicSampleController controller = new PublicSampleController();
        controller.publicSampleService = mock(PublicSampleService.class);
        PublicSamplePage expected = new PublicSamplePage(List.of(), 2, 100, 200, 2);
        when(controller.publicSampleService.getPage(2)).thenReturn(expected);

        assertSame(expected, controller.getSamples(2));
        verify(controller.publicSampleService).getPage(2);
        Method endpoint = PublicSampleController.class.getMethod("getSamples", int.class);
        assertTrue(endpoint.isAnnotationPresent(PermitAll.class));
        assertEquals("/api/public/samples", PublicSampleController.class.getAnnotation(jakarta.ws.rs.Path.class).value());
    }

    @Test
    void rejectsNegativePage() {
        PublicSampleController controller = new PublicSampleController();
        controller.publicSampleService = mock(PublicSampleService.class);

        assertThrows(BadRequestException.class, () -> controller.getSamples(-1));
        verifyNoInteractions(controller.publicSampleService);
    }

    @Test
    void exposesAnonymousSpeciesAndOrganizationRoutes() throws Exception {
        PublicSampleController controller = new PublicSampleController();
        controller.publicSampleService = mock(PublicSampleService.class);
        when(controller.publicSampleService.getSpecies()).thenReturn(List.of(42, 73));
        when(controller.publicSampleService.getOrganizations()).thenReturn(List.of("Org A", "Org B"));

        assertEquals(List.of(42, 73), controller.getSpecies());
        assertEquals(List.of("Org A", "Org B"), controller.getOrganizations());

        Method speciesEndpoint = PublicSampleController.class.getMethod("getSpecies");
        assertTrue(speciesEndpoint.isAnnotationPresent(PermitAll.class));
        assertEquals("/species", speciesEndpoint.getAnnotation(jakarta.ws.rs.Path.class).value());
        Method organizationsEndpoint = PublicSampleController.class.getMethod("getOrganizations");
        assertTrue(organizationsEndpoint.isAnnotationPresent(PermitAll.class));
        assertEquals("/organizations", organizationsEndpoint.getAnnotation(jakarta.ws.rs.Path.class).value());
    }
}