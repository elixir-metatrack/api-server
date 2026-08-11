package no.metatrack.server.stats;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.Sample;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@ApplicationScoped
public class StatisticsService {
    private static final String DAILY_SAMPLE_COUNTS_QUERY = """
            SELECT (created_on AT TIME ZONE 'UTC')::date AS creation_date, COUNT(*) AS sample_count
            FROM sample
            WHERE created_on IS NOT NULL
            GROUP BY creation_date
            ORDER BY creation_date DESC
            """;
    private static final String DISTINCT_CREATION_DATES_QUERY = """
            SELECT COUNT(DISTINCT (created_on AT TIME ZONE 'UTC')::date)
            FROM sample
            WHERE created_on IS NOT NULL
            """;

    @Inject
    EntityManager entityManager;

    public MetatrackStatistics getStatistics() {
        long projectCount = Project.count();
        long sampleCount = Sample.count();
        long assayCount = Assay.count();

        Instant lastUpdated = Sample.<Sample>find("order by createdOn desc")
                .firstResultOptional()
                .map(s -> s.createdOn)
                .orElse(null);

        return new MetatrackStatistics(projectCount, sampleCount, assayCount, lastUpdated);
    }

    public DailySampleCountPage getDailySampleCounts(int page, int size) {
        Number distinctDateCount = (Number) entityManager
                .createNativeQuery(DISTINCT_CREATION_DATES_QUERY)
                .getSingleResult();
        long totalElements = distinctDateCount.longValue();
        long totalPages = totalElements == 0 ? 0 : 1 + (totalElements - 1) / size;
        long offset = (long) page * size;

        if (offset > Integer.MAX_VALUE || offset >= totalElements) {
            return new DailySampleCountPage(List.of(), page, size, totalElements, totalPages);
        }

        Query query = entityManager.createNativeQuery(DAILY_SAMPLE_COUNTS_QUERY)
                .setFirstResult((int) offset)
                .setMaxResults(size);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<DailySampleCount> items = rows.stream()
                .map(row -> new DailySampleCount(toLocalDate(row[0]), ((Number) row[1]).longValue()))
                .toList();

        return new DailySampleCountPage(items, page, size, totalElements, totalPages);
    }

    private LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) return localDate;
        if (value instanceof Date date) return date.toLocalDate();
        throw new IllegalStateException("Unsupported creation date value: " + value);
    }
}
