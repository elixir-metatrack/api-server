package no.metatrack.server.stats;

import java.time.LocalDate;

public record DailySampleCount(LocalDate date, long sampleCount) {}