package no.metatrack.server.sample;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.taxon.TaxonResponse;
import no.metatrack.server.taxon.TaxonomyService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class PublicSampleService {
    private static final int PAGE_SIZE = 100;
    private static final String COUNT_QUERY = "select count(s) from Sample s";
    private static final String SAMPLE_PAGE_QUERY = "select s.id, s.taxId, p.dataOwningOrganization "
            + "from Sample s left join s.project p order by s.id";

    @Inject
    EntityManager entityManager;

    @Inject
    TaxonomyService taxonomyService;

    public PublicSamplePage getPage(int page) {
        if (page < 0) throw new BadRequestException("Page must be zero or greater");

        TypedQuery<Long> countQuery = entityManager.createQuery(COUNT_QUERY, Long.class);
        long totalElements = countQuery.getSingleResult();
        long totalPages = totalElements == 0 ? 0 : 1 + (totalElements - 1) / PAGE_SIZE;
        long offset = (long) page * PAGE_SIZE;

        if (offset > Integer.MAX_VALUE || offset >= totalElements) {
            return new PublicSamplePage(List.of(), page, PAGE_SIZE, totalElements, totalPages);
        }

        List<Object[]> rows = entityManager.createQuery(SAMPLE_PAGE_QUERY, Object[].class)
                .setFirstResult((int) offset)
                .setMaxResults(PAGE_SIZE)
                .getResultList();

        Map<Integer, String> scientificNamesByTaxId = new HashMap<>();
        List<PublicSampleResponse> items = rows.stream()
                .map(row -> toPublicSample(row, scientificNamesByTaxId))
                .toList();

        return new PublicSamplePage(items, page, PAGE_SIZE, totalElements, totalPages);
    }

    public List<Integer> getSpecies() {
        return entityManager.createQuery(
                        "select distinct s.taxId from Sample s where s.taxId is not null order by s.taxId",
                        Integer.class)
                .getResultList();
    }

    public List<String> getOrganizations() {
        return entityManager.createQuery(
                        "select distinct p.dataOwningOrganization from Sample s join s.project p "
                                + "where p.dataOwningOrganization is not null "
                                + "and trim(p.dataOwningOrganization) <> '' "
                                + "order by p.dataOwningOrganization",
                        String.class)
                .getResultList();
    }

    private PublicSampleResponse toPublicSample(Object[] row, Map<Integer, String> scientificNamesByTaxId) {
        UUID id = (UUID) row[0];
        Integer taxId = (Integer) row[1];
        String organization = (String) row[2];
        String scientificName = null;

        if (taxId != null) {
            if (!scientificNamesByTaxId.containsKey(taxId)) {
                String name = null;
                try {
                    TaxonResponse taxon = taxonomyService.getTaxon(taxId.toString());
                    if (taxon != null) name = taxon.name();
                } catch (ProcessingException | WebApplicationException ignored) {
                    // Keep the public sample available if the taxonomy service is unavailable.
                }
                scientificNamesByTaxId.put(taxId, name);
            }
            scientificName = scientificNamesByTaxId.get(taxId);
        }

        return new PublicSampleResponse(id, taxId, scientificName, organization);
    }
}