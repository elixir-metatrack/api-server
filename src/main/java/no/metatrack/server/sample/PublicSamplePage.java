package no.metatrack.server.sample;

import java.util.List;

public record PublicSamplePage(
        List<PublicSampleResponse> items,
        int page,
        int size,
        long totalElements,
        long totalPages) {}