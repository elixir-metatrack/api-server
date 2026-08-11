package no.metatrack.server.stats;

import java.util.List;

public record DailySampleCountPage(
        List<DailySampleCount> items,
        int page,
        int size,
        long totalElements,
        long totalPages) {}