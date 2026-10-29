package no.metatrack.server.sample;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.taxon.TaxonResponse;
import no.metatrack.server.taxon.TaxonomyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PublicSampleServiceTest {
    private final EntityManager entityManager = mock(EntityManager.class);
    private final TaxonomyService taxonomyService = mock(TaxonomyService.class);
    private final PublicSampleService service = new PublicSampleService();
    private TypedQuery<Object[]> pageQuery;

    @BeforeEach
    void setUp() {
        service.entityManager = entityManager;
        service.taxonomyService = taxonomyService;
        TypedQuery<Long> countQuery = mock(TypedQuery.class);
        pageQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(countQuery);
        when(entityManager.createQuery(anyString(), eq(Object[].class))).thenReturn(pageQuery);
        when(pageQuery.setFirstResult(anyInt())).thenReturn(pageQuery);
        when(pageQuery.setMaxResults(anyInt())).thenReturn(pageQuery);
    }

    @Test
    void returnsOnlyPublicFieldsAndResolvesEachTaxIdOncePerPage() {
        setRows(3, List.of(
                new Object[] {uuid(1), 42, "Owning Organization", 9606, LocalDate.of(2024, 12, 31), "Blood"},
                new Object[] {uuid(2), 42, "Another Organization", 42, LocalDate.of(2025, 1, 1), "Soil"},
                row(3, null, null)));
        when(taxonomyService.getTaxon("42")).thenReturn(new TaxonResponse("Bacillus subtilis", "42"));
        when(taxonomyService.getTaxon("9606")).thenReturn(new TaxonResponse("Homo sapiens", "9606"));

        PublicSamplePage result = service.getPage(0);

        assertEquals(0, result.page());
        assertEquals(100, result.size());
        assertEquals(3, result.totalElements());
        assertEquals(1, result.totalPages());
        assertEquals(List.of(
                new PublicSampleResponse(uuid(1), 42, "Bacillus subtilis", "Owning Organization",
                        9606, "Homo sapiens", 2024, "Blood"),
                new PublicSampleResponse(uuid(2), 42, "Bacillus subtilis", "Another Organization",
                        42, "Bacillus subtilis", 2025, "Soil"),
                new PublicSampleResponse(uuid(3), null, null, null, null, null, null, null)), result.items());
        verify(taxonomyService, times(1)).getTaxon("42");
        verify(taxonomyService, times(1)).getTaxon("9606");
        verifyNoMoreInteractions(taxonomyService);
        verify(entityManager).createQuery(
                "select s.id, s.taxId, p.dataOwningOrganization, s.hostTaxId, s.collectionDate, s.isolationSource "
                        + "from Sample s left join s.project p order by s.id", Object[].class);
        verify(pageQuery).setFirstResult(0);
        verify(pageQuery).setMaxResults(100);
    }

    @Test
    void returnsNullScientificNameWhenTaxonomyHasNoMatch() {
        setRows(1, List.<Object[]>of(row(1, 404, "Org")));
        when(taxonomyService.getTaxon("404")).thenReturn(null);

        assertEquals(new PublicSampleResponse(uuid(1), 404, null, "Org", null, null, null, null),
                service.getPage(0).items().getFirst());
    }

    @Test
    void cachesTaxonomyFailuresForTheRestOfThePage() {
        setRows(2, List.of(row(1, 500, "Org"), row(2, 500, "Org")));
        when(taxonomyService.getTaxon("500")).thenThrow(new WebApplicationException("Taxonomy unavailable"));

        PublicSamplePage result = service.getPage(0);

        assertTrue(result.items().stream().allMatch(item -> item.scientificName() == null));
        verify(taxonomyService, times(1)).getTaxon("500");
    }

    @Test
    void cachesUnknownHostTaxaIncludingWhenUsedAsSampleTaxa() {
        setRows(3, List.of(
                new Object[] {uuid(1), null, "Org", 404, null, null},
                new Object[] {uuid(2), null, "Org", 404, null, null},
                row(3, 404, "Org")));
        when(taxonomyService.getTaxon("404")).thenReturn(null);

        PublicSamplePage result = service.getPage(0);

        assertEquals(3, result.items().size());
        assertEquals(404, result.items().getFirst().hostTaxId());
        assertTrue(result.items().stream().allMatch(item -> item.hostScientificName() == null));
        assertNull(result.items().getLast().scientificName());
        verify(taxonomyService, times(1)).getTaxon("404");
        verifyNoMoreInteractions(taxonomyService);
    }

    @Test
    void keepsSamplesWhenHostTaxonomyFails() {
        for (RuntimeException failure : List.of(
                new ProcessingException("Connection failed"), new WebApplicationException("Taxonomy unavailable"))) {
            reset(taxonomyService);
            setRows(2, List.of(
                    new Object[] {uuid(1), 42, "Org", 9606, LocalDate.of(2024, 2, 29), "Blood"},
                    new Object[] {uuid(2), 42, "Org", 9606, null, null}));
            when(taxonomyService.getTaxon("42")).thenReturn(new TaxonResponse("Bacillus subtilis", "42"));
            when(taxonomyService.getTaxon("9606")).thenThrow(failure);

            PublicSamplePage result = service.getPage(0);

            assertEquals(2, result.items().size());
            assertEquals(new PublicSampleResponse(uuid(1), 42, "Bacillus subtilis", "Org",
                    9606, null, 2024, "Blood"), result.items().getFirst());
            assertNull(result.items().getLast().hostScientificName());
            verify(taxonomyService, times(1)).getTaxon("9606");
            verify(taxonomyService, times(1)).getTaxon("42");
            verifyNoMoreInteractions(taxonomyService);
        }
    }

    @Test
    void paginatesInStableHundredRowPages() {
        setRows(250, List.<Object[]>of(row(101, 23, "Org")));

        PublicSamplePage result = service.getPage(1);

        assertEquals(1, result.page());
        assertEquals(100, result.size());
        assertEquals(250, result.totalElements());
        assertEquals(3, result.totalPages());
        verify(pageQuery).setFirstResult(100);
        verify(pageQuery).setMaxResults(100);
    }

    @Test
    void returnsEmptyPageForNoSamplesOrOutOfRangePage() {
        setRows(0, List.of());
        PublicSamplePage emptyResult = service.getPage(0);
        assertEquals(0, emptyResult.totalPages());
        assertTrue(emptyResult.items().isEmpty());
        verify(entityManager, never()).createQuery(contains("left join"), eq(Object[].class));

        reset(entityManager, pageQuery);
        TypedQuery<Long> countQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(250L);
        when(entityManager.createQuery(anyString(), eq(Object[].class))).thenReturn(pageQuery);

        PublicSamplePage outOfRange = service.getPage(3);
        assertTrue(outOfRange.items().isEmpty());
        assertEquals(3, outOfRange.totalPages());
        verify(entityManager, never()).createQuery(contains("left join"), eq(Object[].class));
    }

    @Test
    void returnsEmptyPageWhenOffsetCannotFitJpaIntegerOffset() {
        setRows(Long.MAX_VALUE, List.of());

        assertTrue(service.getPage(Integer.MAX_VALUE).items().isEmpty());
        verify(entityManager, never()).createQuery(contains("left join"), eq(Object[].class));
    }

    @Test
    void rejectsNegativePageBeforeQuerying() {
        assertThrows(BadRequestException.class, () -> service.getPage(-1));
        verifyNoInteractions(entityManager);
    }

    @Test
    void returnsDistinctSampleTaxIdsWithoutLookingUpHostTaxIdsOrTaxonomy() {
        TypedQuery<Integer> speciesQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(Integer.class))).thenReturn(speciesQuery);
        when(speciesQuery.getResultList()).thenReturn(List.of(42, 73));

        assertEquals(List.of(42, 73), service.getSpecies());
        verify(entityManager).createQuery(
                "select distinct s.taxId from Sample s where s.taxId is not null order by s.taxId", Integer.class);
        verifyNoInteractions(taxonomyService);
    }

    @Test
    void returnsDistinctNonBlankOwningOrganizationsWithoutTaxonomyLookups() {
        TypedQuery<String> organizationsQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(String.class))).thenReturn(organizationsQuery);
        when(organizationsQuery.getResultList()).thenReturn(List.of("Org A", "Org B"));

        assertEquals(List.of("Org A", "Org B"), service.getOrganizations());
        verify(entityManager).createQuery(
                "select distinct p.dataOwningOrganization from Sample s join s.project p "
                        + "where p.dataOwningOrganization is not null "
                        + "and trim(p.dataOwningOrganization) <> '' "
                        + "order by p.dataOwningOrganization",
                String.class);
        verifyNoInteractions(taxonomyService);
    }

    @Test
    void returnsEmptyFilterListsWhenThereAreNoMatchingValues() {
        TypedQuery<Integer> speciesQuery = mock(TypedQuery.class);
        TypedQuery<String> organizationsQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(Integer.class))).thenReturn(speciesQuery);
        when(entityManager.createQuery(anyString(), eq(String.class))).thenReturn(organizationsQuery);
        when(speciesQuery.getResultList()).thenReturn(List.of());
        when(organizationsQuery.getResultList()).thenReturn(List.of());

        assertTrue(service.getSpecies().isEmpty());
        assertTrue(service.getOrganizations().isEmpty());
        verifyNoInteractions(taxonomyService);
    }

    @SuppressWarnings("unchecked")
    private void setRows(long total, List<Object[]> rows) {
        TypedQuery<Long> countQuery = (TypedQuery<Long>) mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(total);
        when(pageQuery.getResultList()).thenReturn(rows);
    }

    private static Object[] row(int id, Integer taxId, String organization) {
        return new Object[] {uuid(id), taxId, organization, null, null, null};
    }

    private static UUID uuid(int leastSignificantBits) {
        return new UUID(0, leastSignificantBits);
    }
}