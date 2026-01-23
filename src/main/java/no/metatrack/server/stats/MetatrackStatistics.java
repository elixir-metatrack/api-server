package no.metatrack.server.stats;

import java.time.Instant;

public record MetatrackStatistics(long projectCount, long sampleCount, long assayCount, Instant lastUpdated) {}
