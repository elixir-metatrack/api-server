package no.metatrack.server.stats;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetatrackStatistics(long projectCount, long sampleCount, long assayCount, Instant lastUpdated) {}
