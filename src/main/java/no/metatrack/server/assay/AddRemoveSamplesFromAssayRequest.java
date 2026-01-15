package no.metatrack.server.assay;

import java.util.List;

public record AddRemoveSamplesFromAssayRequest(List<String> sampleNames) {}
