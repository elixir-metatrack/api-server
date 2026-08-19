package no.metatrack.server.stats;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import no.metatrack.server.file.File;
import no.metatrack.server.file.ObjectStorage;
import no.metatrack.server.file.StorageObjectMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class StatisticsServiceTest {
    private EntityManager entityManager;
    private Query countQuery;
    private Query dataQuery;
    private ObjectStorage objectStorage;
    private StatisticsService service;

    @BeforeEach
    void setUp() {
        entityManager = mock(EntityManager.class);
        countQuery = mock(Query.class);
        dataQuery = mock(Query.class);
        objectStorage = mock(ObjectStorage.class);
        service = new StatisticsService();
        service.entityManager = entityManager;
        service.objectStorage = objectStorage;

        when(entityManager.createNativeQuery(contains("COUNT(DISTINCT"))).thenReturn(countQuery);
        when(entityManager.createNativeQuery(contains("GROUP BY creation_date"))).thenReturn(dataQuery);
        when(dataQuery.setFirstResult(anyInt())).thenReturn(dataQuery);
        when(dataQuery.setMaxResults(anyInt())).thenReturn(dataQuery);
    }

    @Test
    void returnsGroupedCountsNewestFirstWithPartialPageMetadata() {
        when(countQuery.getSingleResult()).thenReturn(3L);
        when(dataQuery.getResultList()).thenReturn(List.of(
                new Object[]{LocalDate.of(2026, 8, 11), 2L},
                new Object[]{Date.valueOf("2026-08-10"), 1L}));

        DailySampleCountPage result = service.getDailySampleCounts(0, 2);

        assertEquals(List.of(
                new DailySampleCount(LocalDate.of(2026, 8, 11), 2),
                new DailySampleCount(LocalDate.of(2026, 8, 10), 1)), result.items());
        assertEquals(0, result.page());
        assertEquals(2, result.size());
        assertEquals(3, result.totalElements());
        assertEquals(2, result.totalPages());
        verify(dataQuery).setFirstResult(0);
        verify(dataQuery).setMaxResults(2);
    }

    @Test
    void appliesPageOffsetToDateBuckets() {
        when(countQuery.getSingleResult()).thenReturn(5L);
        when(dataQuery.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{LocalDate.of(2026, 8, 9), 4L}));

        DailySampleCountPage result = service.getDailySampleCounts(1, 2);

        assertEquals(List.of(new DailySampleCount(LocalDate.of(2026, 8, 9), 4)), result.items());
        assertEquals(3, result.totalPages());
        verify(dataQuery).setFirstResult(2);
    }

    @Test
    void returnsEmptyPageWhenNoSamplesExist() {
        when(countQuery.getSingleResult()).thenReturn(0L);

        DailySampleCountPage result = service.getDailySampleCounts(0, 20);

        assertEquals(List.of(), result.items());
        assertEquals(0, result.totalElements());
        assertEquals(0, result.totalPages());
        verify(entityManager, never()).createNativeQuery(contains("GROUP BY creation_date"));
    }

    @Test
    void returnsEmptyPageWhenPageIsOutOfRange() {
        when(countQuery.getSingleResult()).thenReturn(2L);

        DailySampleCountPage result = service.getDailySampleCounts(2, 2);

        assertEquals(List.of(), result.items());
        assertEquals(2, result.totalElements());
        assertEquals(1, result.totalPages());
        verify(entityManager, never()).createNativeQuery(contains("GROUP BY creation_date"));
    }

    @Test
    void returnsEmptyPageWhenOffsetExceedsJpaLimit() {
        when(countQuery.getSingleResult()).thenReturn((long) Integer.MAX_VALUE + 2);

        DailySampleCountPage result = service.getDailySampleCounts(Integer.MAX_VALUE, 2);

        assertEquals(List.of(), result.items());
        verify(entityManager, never()).createNativeQuery(contains("GROUP BY creation_date"));
    }

    @Test
    void aggregatesOnlyTrackedUploadedObjects() {
        try (MockedStatic<File> files = mockStatic(File.class)) {
            files.when(File::findUploadedObjectKeys).thenReturn(List.of("tracked", "missing"));
            when(objectStorage.listObjects(null)).thenReturn(List.of(
                    new StorageObjectMetadata("tracked", 12L),
                    new StorageObjectMetadata("untracked", 99L)));

            assertEquals(new StorageStatistics(1L, 12L), service.getStorageStatistics());
        }
    }

    @Test
    void scopesProjectListingAndPreventsDuplicateCounts() {
        try (MockedStatic<File> files = mockStatic(File.class)) {
            files.when(() -> File.findUploadedObjectKeysInProject(42L))
                    .thenReturn(List.of("42/tracked", "42/tracked"));
            when(objectStorage.listObjects("42/")).thenReturn(List.of(
                    new StorageObjectMetadata("42/tracked", 15L),
                    new StorageObjectMetadata("42/tracked", 15L)));

            assertEquals(new StorageStatistics(1L, 15L), service.getProjectStorageStatistics(42L));
        }
    }

    @Test
    void returnsZeroStorageStatisticsForEmptyScope() {
        try (MockedStatic<File> files = mockStatic(File.class)) {
            files.when(File::findUploadedObjectKeys).thenReturn(List.of());
            when(objectStorage.listObjects(null)).thenReturn(List.of());

            assertEquals(new StorageStatistics(0L, 0L), service.getStorageStatistics());
        }
    }
}